/*
 * Lumos - android-app/app / One relay pass: be registered, send the outbox, take in the mailbox.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-005, SRS-RMT-009, SRS-RMT-015, SRS-RMT-016, SRS-RMT-017, SRS-RMT-018, TRS-TRN-012, TRS-PAT-004, TRS-TST-006
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: Every step is ordered so that dying between any two of them loses
 * nothing and shows nothing twice (SRS-RMT-017, SRS-RMT-004):
 *
 * - Sending: an outbox item is removed only AFTER the relay took it. Dying
 *   after the relay took it but before the removal means the next pass sends
 *   the same id with the same bytes, which the relay answers as the same
 *   success and does not store twice (its idempotency, TRS-BCK-004).
 * - Receiving: an envelope is acknowledged only AFTER ChatStore.receive has
 *   committed. Dying in between means the next pass fetches it again, and the
 *   store's dedup answers DUPLICATE, so it is acknowledged then and shown once.
 *   Everything the store refuses - tampered, replayed, unknown kind, expired,
 *   from a conversation this device no longer has - is acknowledged too, so a
 *   bad envelope cannot sit in the mailbox being refetched forever
 *   (SRS-RMT-009). Only a local engine failure leaves an envelope unacknowledged.
 *
 * Order is kept per conversation: once one conversation's item cannot go (no
 * route yet, the peer's mailbox full), its later items wait too, while other
 * conversations carry on. Being offline, being rate limited and a refused
 * credential stop the whole pass; nothing is lost, the next pass resumes.
 * A refused credential also drops the registration, so the next pass
 * registers afresh, republishes prekeys and re-allows every route. The relay
 * is used whether or not any nearby radio is on (SRS-RMT-018, TRS-TRN-012).
 * Delivered and read never come from here: the relay taking a send is one
 * tick, nothing more (owner ruling 2026-11-21). They come from the peer's
 * receipts, which travel the same way under their own classes (CR-005) and
 * are opened by ChatStore.receiveReceipt.
 */

package com.lumos.app.relay;

import com.lumos.app.chat.ChatRecords;
import com.lumos.app.chat.ChatStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One relay pass. */
public final class RelaySync {

    /** Fetch batches taken per pass; the caller runs again when more may wait. */
    static final int MAX_BATCHES = 5;
    private static final long MILLIS_PER_SECOND = 1000L;

    private final ChatStore chat;
    private final RelayRoutes routes;
    private final RelayAccount account;
    private final PrekeyPublisher prekeys;
    private final RelayClient anonymous;
    private final LocalStore.Clock clock;

    private RelaySync(final ChatStore chat, final RelayRoutes routes, final RelayAccount account,
            final PrekeyPublisher prekeys, final RelayClient anonymous, final LocalStore.Clock clock) {
        this.chat = chat;
        this.routes = routes;
        this.account = account;
        this.prekeys = prekeys;
        this.anonymous = anonymous;
        this.clock = clock;
    }

    /**
     * The parts a pass works over.
     *
     * @param chat the chat store
     * @param routes the conversations' routes
     * @param account the registration
     * @param prekeys the prekey publisher
     * @param anonymous a client with no credential, over the transport
     * @param clock the UTC clock
     * @return the sync
     */
    public static RelaySync over(final ChatStore chat, final RelayRoutes routes, final RelayAccount account,
            final PrekeyPublisher prekeys, final RelayClient anonymous, final LocalStore.Clock clock) {
        return new RelaySync(chat, routes, account, prekeys, anonymous, clock);
    }

    /**
     * Why a pass stopped early.
     *
     * @param kind what the caller does next
     * @param code the stable code, or empty; diagnostics only
     * @param retryAfterSeconds the server's Retry-After, or -1
     */
    public record Stop(RelayException.Kind kind, String code, long retryAfterSeconds) {

        static Stop of(final RelayException e) {
            return new Stop(e.kind(), e.code(), e.retryAfterSeconds());
        }

        /** @return true when the pass should simply run again later */
        public boolean retryable() {
            return kind == RelayException.Kind.OFFLINE || kind == RelayException.Kind.BUSY;
        }
    }

    /**
     * What one pass did.
     *
     * @param sent sends the relay took
     * @param failed sends the relay refused for good (shown as failed)
     * @param shown envelopes that showed a message or applied an event
     * @param acknowledged envelopes acknowledged and so deleted from the relay
     * @param moreWaiting true when the mailbox may hold more than this pass took
     * @param stoppedBy why the pass stopped early, or null when it ran to the end
     */
    public record Report(int sent, int failed, int shown, int acknowledged, boolean moreWaiting, Stop stoppedBy) {

        /** @return true when the pass stopped on something worth retrying as is */
        public boolean retryLater() {
            return stoppedBy != null && stoppedBy.retryable();
        }
    }

    /** Counts kept while a pass runs. */
    private static final class Tally {
        private int sent;
        private int failed;
        private int shown;
        private int acknowledged;
        private boolean more;

        Report report(final RelayException stop) {
            return new Report(sent, failed, shown, acknowledged, more, stop == null ? null : Stop.of(stop));
        }
    }

    /**
     * Runs one pass. A relay failure ends the pass and is reported, never
     * thrown; a local engine failure is thrown after everything already
     * committed has been acknowledged.
     *
     * @return what the pass did
     * @throws StoreException on a local engine failure
     * @throws GeneralSecurityException when the device or prekey keys could not be made
     */
    public Report run() throws StoreException, GeneralSecurityException {
        final Tally t = new Tally();
        try {
            final RelayClient client = ready();
            send(client, t);
            receive(client, t);
            return t.report(null);
        } catch (final RelayException e) {
            if (e.kind() == RelayException.Kind.AUTH) {
                account.forget();
            }
            return t.report(e);
        }
    }

    /**
     * Stops a conversation's peer from sending here (unmatch, block -
     * SRS-RMT-016): revokes the capability this device issued it at the
     * relay, then forgets the route. If the relay cannot be reached the
     * route is kept, so calling again later finishes the job; the caller
     * removes the conversation itself, so anything the peer sends meanwhile
     * opens into no conversation and is dropped.
     *
     * @param matchId the conversation
     * @return null when done (or nothing to do), otherwise why it stopped
     * @throws StoreException on a local engine failure
     * @throws GeneralSecurityException when the device key could not be made
     */
    public Stop forget(final byte[] matchId) throws StoreException, GeneralSecurityException {
        final Optional<RelayRoutes.Route> route = routes.forMatch(matchId);
        if (!route.isPresent()) {
            return null;
        }
        try {
            anonymous.as(account.ensure(anonymous).credential()).revoke(route.get().proof());
            routes.remove(matchId);
            return null;
        } catch (final RelayException e) {
            if (e.kind() == RelayException.Kind.AUTH) {
                account.forget();
            }
            return Stop.of(e);
        }
    }

    /** Registration, prekeys and every route's proof, as the relay must hold them. */
    private RelayClient ready() throws RelayException, StoreException, GeneralSecurityException {
        final RelayTypes.Registration reg = account.ensure(anonymous);
        final RelayClient client = anonymous.as(reg.credential());
        prekeys.ensure(client, reg);
        for (final RelayRoutes.Route r : routes.all()) {
            if (!Arrays.equals(r.allowedFor(), reg.routingId())) {
                client.allow(r.proof());
                routes.markAllowed(r.matchId(), reg.routingId());
            }
        }
        return client;
    }

    private void send(final RelayClient client, final Tally t) throws RelayException, StoreException {
        final Set<String> held = new HashSet<>();
        for (final ChatRecords.OutboxItem item : chat.pendingOutbox()) {
            final String conversation = Hex.encode(item.matchId());
            if (held.contains(conversation)) {
                continue;
            }
            final Optional<RelayRoutes.Route> route = routes.forMatch(item.matchId());
            if (!route.isPresent() || !route.get().canSend()) {
                held.add(conversation);
                continue;
            }
            try {
                client.submit(new RelayTypes.Send(route.get().peerRoutingId(), item.messageId(),
                        item.messageClass().code(), route.get().sendCapability(), item.envelope()));
                chat.markAccepted(item.messageId());
                t.sent++;
            } catch (final RelayException e) {
                if (e.kind() == RelayException.Kind.REFUSED) {
                    chat.markFailed(item.messageId());
                    t.failed++;
                } else if (LumosErrors.ERR_RELAY_MAILBOX_FULL.equals(e.code())) {
                    // This peer's mailbox cannot take it now; others still can.
                    held.add(conversation);
                } else {
                    throw e;
                }
            }
        }
    }

    private void receive(final RelayClient client, final Tally t) throws RelayException, StoreException {
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            final List<RelayTypes.Envelope> envelopes = client.fetch();
            if (envelopes.isEmpty()) {
                t.more = false;
                return;
            }
            final Map<String, byte[]> byProof = routes.byProof();
            final List<byte[]> done = new ArrayList<>();
            try {
                for (final RelayTypes.Envelope e : envelopes) {
                    if (open(byProof, e)) {
                        t.shown++;
                    }
                    done.add(e.messageId());
                }
            } catch (final StoreException local) {
                acknowledgeBeforeFailing(client, done, t);
                throw local;
            }
            t.acknowledged += (int) client.ack(done);
            t.more = envelopes.size() == RelayClient.FETCH_BATCH;
            if (!t.more) {
                return;
            }
        }
    }

    /**
     * Opens one envelope under the class it was delivered as; true when a
     * text showed something. Receipts move ticks and show nothing; anything
     * else, or anything not for an open conversation, is dropped.
     */
    private boolean open(final Map<String, byte[]> byProof, final RelayTypes.Envelope e) throws StoreException {
        final byte[] matchId = byProof.get(Hex.encode(e.senderProof()));
        final boolean expired = e.expiresAt() * MILLIS_PER_SECOND <= clock.nowUtcMillis();
        final MessageClass cls = MessageClass.fromCode(e.messageClass());
        if (matchId == null || expired || cls == null) {
            return false;
        }
        switch (cls) {
            case TEXT_MESSAGE:
                final ChatStore.Outcome o = chat.receive(matchId, e.messageId(), e.payload()).outcome();
                return o == ChatStore.Outcome.DISPLAYED || o == ChatStore.Outcome.APPLIED;
            case DELIVERY_RECEIPT:
            case READ_RECEIPT:
                chat.receiveReceipt(matchId, e.messageId(), cls, e.payload());
                return false;
            default:
                return false;
        }
    }

    /**
     * Acknowledges what already committed before a local failure ends the
     * pass. If this ack is lost too, the envelopes come back and dedup
     * answers them; nothing is shown twice either way.
     */
    private static void acknowledgeBeforeFailing(final RelayClient client, final List<byte[]> done, final Tally t) {
        if (done.isEmpty()) {
            return;
        }
        try {
            t.acknowledged += (int) client.ack(done);
        } catch (final RelayException ignored) {
            // Refetched next pass and answered by dedup.
        }
    }
}

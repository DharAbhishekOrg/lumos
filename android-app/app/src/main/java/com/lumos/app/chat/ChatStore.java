/*
 * Lumos - android-app/app / Conversations, messages, the outbox and ratchet sessions, each change one transaction.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-005, SRS-RMT-009, SRS-RMT-010, SRS-RMT-011, SRS-RMT-014, SRS-RMT-017, SRS-NFR-SEC-006, TRS-LOC-004, TRS-LOC-005, TRS-PAT-004, TRS-CRY-005
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: The rule everything here follows is TRS Sec. 8.3: a change that
 * touches several records is ONE transaction. Sending seals the envelope,
 * saves the advanced ratchet, writes the visible message and writes its
 * outbox item together - so after a crash there is either a queued message
 * with the exact bytes to resend, or nothing (TRS-PAT-004, TRS-LOC-004).
 * Receiving opens the envelope, saves the advanced ratchet, deletes a
 * consumed one-time prekey (TRS-CRY-005), applies the event and records the
 * id as seen, together - so a restart can never return to a ratchet that
 * still holds a key already used (SRS-RMT-017), and a message shows at most
 * once (SRS-RMT-004).
 *
 * Nothing is cached between calls: each operation restores its session from
 * the store and saves it only on success. A failed decrypt changes the
 * in-memory ratchet, and throwing it away is the rollback. An event kind this
 * build does not know (a newer app) decrypts fine; it is consumed and ignored,
 * never retried (SRS-RMT-011). Edits and deletes apply only to the sender's
 * own messages; an event whose target is unknown is ignored.
 *
 * Receipts (WP-05 S7e2, CR-005) ride the same sessions. Showing a message
 * seals its delivered receipt into the outbox in the same transaction, so a
 * crash cannot show a message and lose its receipt; markRead sends read
 * receipts only when the user allows them (SRS-RMT-014), and only the
 * recipient's receipts ever move a message past one tick (owner ruling
 * 2026-11-21). The ratchet mechanics live in ChatSessions.
 */

package com.lumos.app.chat;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.Receipt;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.x3dh.X3dh;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** The device's chat state. */
public final class ChatStore {

    private final LocalStore store;
    private final RemoteSession.Identity own;
    private final LocalStore.Clock clock;
    private final ChatSessions sessions;

    /** What receiving one envelope did. */
    public enum Outcome {
        /** A new message is shown. */
        DISPLAYED,
        /** An edit, delete or reaction was applied to its target. */
        APPLIED,
        /** This id was already shown; nothing changed. */
        DUPLICATE,
        /** Opened and consumed, but nothing to show: unknown kind or unknown target. */
        IGNORED,
        /** Refused; nothing changed. The code says why, for diagnostics only. */
        REJECTED
    }

    /**
     * The result of receiving one envelope.
     *
     * @param outcome what happened
     * @param code the stable code when REJECTED, otherwise empty
     */
    public record Received(Outcome outcome, String code) { }

    private ChatStore(final LocalStore store, final RemoteSession.Identity own, final LocalStore.Clock clock,
            final DoubleRatchet.KeyProvider ratchetKeys) {
        this.store = store;
        this.own = own;
        this.clock = clock;
        this.sessions = ChatSessions.over(store, own, clock, ratchetKeys);
    }

    /**
     * Binds the chat state to the device's store. The store is shared on
     * purpose: chat writes join the same transactions as everything else.
     *
     * @param store the sealed local store
     * @param own this device's identity
     * @param clock the UTC clock
     * @param ratchetKeys fresh ratchet scalars from the platform CSPRNG
     * @return the chat store
     */
    public static ChatStore over(final LocalStore store, final RemoteSession.Identity own,
            final LocalStore.Clock clock, final DoubleRatchet.KeyProvider ratchetKeys) {
        return new ChatStore(store, own, clock, ratchetKeys);
    }

    // ---- prekeys and conversations ----------------------------------------------------------

    /**
     * Stores a private prekey this device published.
     *
     * @param secret the prekey
     * @throws StoreException on engine failure
     */
    public void putPrekeySecret(final PrekeySecret secret) throws StoreException {
        store.putByNaturalKey(RecordKind.PREKEY_SECRET, secret.encode(),
                PrekeySecret.naturalKey(secret.oneTime(), secret.id()));
    }

    /**
     * Pins a matched peer's identity, so its first message can be accepted.
     *
     * @param matchId the match identifier
     * @param peerIdentityEd the peer's Ed25519 identity from the match
     * @throws StoreException on engine failure
     */
    public void acceptConversation(final byte[] matchId, final byte[] peerIdentityEd) throws StoreException {
        store.putByNaturalKey(RecordKind.CONVERSATION, new ChatRecords.Conversation(matchId, peerIdentityEd).encode(),
                matchId);
    }

    /**
     * The identity a match pinned for its peer: what a send must address.
     *
     * @param matchId the conversation
     * @return the peer's Ed25519 identity, or empty for a conversation this device never accepted
     * @throws StoreException on engine failure
     */
    public Optional<byte[]> peerIdentity(final byte[] matchId) throws StoreException {
        return conversation(matchId).map(ChatRecords.Conversation::peerIdentityEd);
    }

    private Optional<ChatRecords.Conversation> conversation(final byte[] matchId) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.CONVERSATION, matchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(ChatRecords.Conversation.decode(rows.get(0)));
    }

    /**
     * Starts a session with a matched peer from its fetched bundle. The
     * bundle must belong to the identity the match pinned.
     *
     * @param matchId the match identifier
     * @param peerIdentityEd the peer's identity from the match
     * @param bundle the bundle the registry returned
     * @param ephemeralPriv a fresh ephemeral scalar
     * @param ratchetPriv a fresh first ratchet scalar
     * @throws Frame.WireException when the bundle is not the matched peer's, or does not verify
     * @throws StoreException on engine failure
     */
    public void startSession(final byte[] matchId, final byte[] peerIdentityEd, final X3dh.Bundle bundle,
            final byte[] ephemeralPriv, final byte[] ratchetPriv) throws Frame.WireException, StoreException {
        if (!Arrays.equals(bundle.identityEd(), peerIdentityEd)) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_PREKEY_SIGNATURE_INVALID,
                    "the bundle is not the matched peer's");
        }
        final RemoteSession.Started started = RemoteSession.initiate(own, bundle, matchId, ephemeralPriv, ratchetPriv);
        final byte[] prekey = ChatSessions.encodePrekey(started.prekey());
        store.transaction(() -> {
            acceptConversation(matchId, peerIdentityEd);
            sessions.save(matchId, started.ratchet(), prekey);
            return null;
        });
    }

    /**
     * Whether a session exists with this match.
     *
     * @param matchId the match identifier
     * @return true once a session was started or accepted
     * @throws StoreException on engine failure
     */
    public boolean hasSession(final byte[] matchId) throws StoreException {
        return sessions.session(matchId).isPresent();
    }

    // ---- sending ----------------------------------------------------------------------------

    /**
     * Seals and queues one event. The message, the advanced ratchet and the
     * outbox item land together or not at all.
     *
     * @param matchId the conversation
     * @param event the event to send
     * @return the new message id
     * @throws Frame.WireException on a sealing failure
     * @throws StoreException with no session, an invalid target, or an engine failure
     */
    public byte[] send(final byte[] matchId, final RemoteText.TextEvent event) throws Frame.WireException, StoreException {
        final byte[] id = ChatRecords.newMessageId();
        final Frame.WireException[] sealFailure = new Frame.WireException[1];
        try {
            store.transaction(() -> {
                final Optional<ChatRecords.Message> target = targetFor(matchId, event, true);
                final byte[] envelope = sessions.seal(matchId, (r, prekey) -> RemoteSession.seal(r,
                        new RemoteText.Plaintext(id, event), prekey, sessions.nowSeconds()), sealFailure);
                applyOwn(matchId, id, event, target);
                enqueue(matchId, id, envelope, MessageClass.TEXT_MESSAGE);
                return null;
            });
        } catch (final StoreException e) {
            if (sealFailure[0] != null) {
                throw sealFailure[0];
            }
            throw e;
        }
        return id;
    }

    /** Writes an outbox item, last in the device-wide first-in-first-out order. */
    private void enqueue(final byte[] matchId, final byte[] id, final byte[] envelope, final MessageClass cls)
            throws StoreException {
        final long order = pendingOutbox().stream().mapToLong(ChatRecords.OutboxItem::sequence).max().orElse(0) + 1;
        store.put(RecordKind.OUTBOX_ITEM, ChatRecords.rowId(id),
                new ChatRecords.OutboxItem(id, matchId, envelope, clock.nowUtcMillis(), order, 0, cls).encode(), null);
    }

    private Optional<ChatRecords.Message> targetFor(final byte[] matchId, final RemoteText.TextEvent event,
            final boolean mine) throws StoreException {
        if (event.kind() == TextEventKind.TEXT) {
            return Optional.empty();
        }
        final Optional<ChatRecords.Message> t = message(event.target());
        final boolean authorOnly = event.kind() == TextEventKind.EDIT || event.kind() == TextEventKind.DELETE_FOR_EVERYONE;
        final boolean valid = t.isPresent() && Arrays.equals(t.get().matchId(), matchId) && !t.get().deleted()
                && (!authorOnly || t.get().outgoing() == mine);
        if (!valid && mine) {
            throw new StoreException(StoreException.Reason.ENGINE, "that message cannot be the target of this event");
        }
        return valid ? t : Optional.empty();
    }

    private void applyOwn(final byte[] matchId, final byte[] id, final RemoteText.TextEvent event,
            final Optional<ChatRecords.Message> target) throws StoreException {
        if (ChatEvents.isNewMessage(event)) {
            putMessage(new ChatRecords.Message(id, matchId, true, event.body(), clock.nowUtcMillis(), nextSequence(matchId),
                    DeliveryMachine.State.QUEUED, false, false, new byte[0], new byte[0]));
            return;
        }
        putMessage(ChatEvents.applied(target.orElseThrow(), event, true));
    }

    /** The next position in a conversation: one past the highest, assigned inside the write's transaction. */
    private long nextSequence(final byte[] matchId) throws StoreException {
        return messages(matchId).stream().mapToLong(ChatRecords.Message::sequence).max().orElse(0) + 1;
    }

    private void putMessage(final ChatRecords.Message m) throws StoreException {
        store.put(RecordKind.CHAT_MESSAGE, ChatRecords.rowId(m.messageId()), m.encode(), m.matchId());
    }

    private Optional<ChatRecords.Message> message(final byte[] id) throws StoreException {
        final Optional<byte[]> row = store.get(RecordKind.CHAT_MESSAGE, ChatRecords.rowId(id));
        return row.isPresent() ? Optional.of(ChatRecords.Message.decode(row.get())) : Optional.empty();
    }

    // ---- receiving --------------------------------------------------------------------------

    /**
     * Opens one envelope the relay delivered under outerId.
     *
     * @param matchId the conversation the envelope arrived for
     * @param outerId the id the relay delivered it under
     * @param envelopeBytes the relay payload
     * @return what happened
     * @throws StoreException on an engine failure (a bad envelope is REJECTED, not thrown)
     */
    public Received receive(final byte[] matchId, final byte[] outerId, final byte[] envelopeBytes)
            throws StoreException {
        if (outerId.length != RemoteText.MESSAGE_ID_SIZE) {
            return new Received(Outcome.REJECTED, LumosErrors.ERR_PAYLOAD_MALFORMED);
        }
        if (store.get(RecordKind.MESSAGE_DEDUP, ChatRecords.rowId(outerId)).isPresent()) {
            return new Received(Outcome.DUPLICATE, "");
        }
        final RemoteText.Envelope envelope;
        try {
            envelope = RemoteText.decodeEnvelope(envelopeBytes);
        } catch (final Frame.WireException e) {
            return new Received(Outcome.REJECTED, e.code());
        }
        final String[] refusal = new String[1];
        try {
            return store.transaction(() -> {
                try {
                    return openAndApply(matchId, outerId, envelope);
                } catch (final Frame.WireException e) {
                    refusal[0] = e.code();
                    throw new StoreException(StoreException.Reason.ENGINE, "envelope refused");
                }
            });
        } catch (final StoreException e) {
            if (refusal[0] != null) {
                return new Received(Outcome.REJECTED, refusal[0]);
            }
            throw e;
        }
    }

    private Received openAndApply(final byte[] matchId, final byte[] outerId, final RemoteText.Envelope envelope)
            throws Frame.WireException, StoreException {
        final byte[] plaintext = sessions.open(conversationOrRefuse(matchId), outerId, envelope);
        final RemoteText.Plaintext p;
        try {
            p = RemoteText.decodePlaintext(plaintext);
        } catch (final Frame.WireException e) {
            if (LumosErrors.ERR_TEXT_UNKNOWN_EVENT_KIND.equals(e.code())) {
                return new Received(Outcome.IGNORED, ""); // consumed, safely ignored (SRS-RMT-011)
            }
            throw e;
        }
        final Received r = applyPeer(matchId, outerId, p.event());
        if (r.outcome() == Outcome.DISPLAYED) {
            queueReceipt(matchId, MessageClass.DELIVERY_RECEIPT, List.of(outerId));
        }
        return r;
    }

    private ChatRecords.Conversation conversationOrRefuse(final byte[] matchId) throws Frame.WireException, StoreException {
        return conversation(matchId).orElseThrow(
                () -> new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "no such conversation"));
    }

    private Received applyPeer(final byte[] matchId, final byte[] id, final RemoteText.TextEvent event)
            throws StoreException {
        if (ChatEvents.isNewMessage(event)) {
            // A reply is the other person's words: it always shows, whatever its target (CR-009).
            putMessage(new ChatRecords.Message(id, matchId, false, event.body(), clock.nowUtcMillis(), nextSequence(matchId),
                    DeliveryMachine.State.DELIVERED, false, false, new byte[0], new byte[0]));
            return new Received(Outcome.DISPLAYED, "");
        }
        final Optional<ChatRecords.Message> target = targetFor(matchId, event, false);
        if (target.isEmpty()) {
            return new Received(Outcome.IGNORED, "");
        }
        putMessage(ChatEvents.applied(target.get(), event, false));
        return new Received(Outcome.APPLIED, "");
    }

    // ---- receipts (CR-005) ------------------------------------------------------------------

    /** Seals one receipt into the outbox, inside the caller's transaction. */
    private void queueReceipt(final byte[] matchId, final MessageClass kind, final List<byte[]> ids)
            throws StoreException {
        final byte[] id = ChatRecords.newMessageId();
        final Frame.WireException[] sealFailure = new Frame.WireException[1];
        final byte[] envelope = sessions.seal(matchId,
                (r, prekey) -> new Receipt(id, kind, ids).seal(r, prekey, sessions.nowSeconds()), sealFailure);
        enqueue(matchId, id, envelope, kind);
    }

    /**
     * The user has seen a conversation: its unread incoming messages become
     * read, and read receipts go to the peer only when the user allows them
     * (SRS-RMT-014; delivered receipts were already sent on arrival).
     *
     * @param matchId the conversation
     * @param readReceiptsEnabled the user's read-receipt setting
     * @return how many messages became read
     * @throws StoreException on engine failure
     */
    public int markRead(final byte[] matchId, final boolean readReceiptsEnabled) throws StoreException {
        return store.transaction(() -> {
            final List<byte[]> read = new ArrayList<>();
            for (final ChatRecords.Message m : messages(matchId)) {
                if (!m.outgoing() && m.state() == DeliveryMachine.State.DELIVERED) {
                    putMessage(m.withState(DeliveryMachine.State.READ));
                    read.add(m.messageId());
                }
            }
            if (DeliveryMachine.receiptsToSend(readReceiptsEnabled, !read.isEmpty()).read()) {
                for (int i = 0; i < read.size(); i += LumosConstants.RECEIPT_IDS_PER_ENVELOPE_DEFAULT) {
                    queueReceipt(matchId, MessageClass.READ_RECEIPT,
                            read.subList(i, Math.min(read.size(), i + LumosConstants.RECEIPT_IDS_PER_ENVELOPE_DEFAULT)));
                }
            }
            return read.size();
        });
    }

    /**
     * Opens one receipt the relay delivered under outerId and kind, and moves
     * this conversation's own messages it names to delivered or read. A
     * receipt never downgrades a message (DeliveryMachine), and one naming a
     * message from another conversation, or the peer's own, changes nothing.
     *
     * @param matchId the conversation the envelope arrived for
     * @param outerId the id the relay delivered it under
     * @param kind DELIVERY_RECEIPT or READ_RECEIPT, as delivered
     * @param envelopeBytes the relay payload
     * @return APPLIED, DUPLICATE, or REJECTED with its code
     * @throws StoreException on an engine failure
     */
    public Received receiveReceipt(final byte[] matchId, final byte[] outerId, final MessageClass kind,
            final byte[] envelopeBytes) throws StoreException {
        if (outerId.length != RemoteText.MESSAGE_ID_SIZE) {
            return new Received(Outcome.REJECTED, LumosErrors.ERR_PAYLOAD_MALFORMED);
        }
        if (store.get(RecordKind.MESSAGE_DEDUP, ChatRecords.rowId(outerId)).isPresent()) {
            return new Received(Outcome.DUPLICATE, "");
        }
        final String[] refusal = new String[1];
        try {
            return store.transaction(() -> {
                try {
                    final Receipt r = Receipt.decode(sessions.open(conversationOrRefuse(matchId), outerId,
                            RemoteText.decodeEnvelope(envelopeBytes)));
                    if (r.kind() != kind) {
                        throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "receipt kind differs");
                    }
                    applyReceipt(matchId, r);
                    return new Received(Outcome.APPLIED, "");
                } catch (final Frame.WireException e) {
                    refusal[0] = e.code();
                    throw new StoreException(StoreException.Reason.ENGINE, "receipt refused");
                }
            });
        } catch (final StoreException e) {
            if (refusal[0] != null) {
                return new Received(Outcome.REJECTED, refusal[0]);
            }
            throw e;
        }
    }

    private void applyReceipt(final byte[] matchId, final Receipt r) throws StoreException {
        final DeliveryMachine.Event event = r.kind() == MessageClass.READ_RECEIPT
                ? DeliveryMachine.Event.READ_RECEIPT : DeliveryMachine.Event.DELIVERY_RECEIPT;
        for (final byte[] id : r.acked()) {
            final Optional<ChatRecords.Message> m = message(id);
            if (m.isPresent() && m.get().outgoing() && Arrays.equals(m.get().matchId(), matchId)) {
                putMessage(m.get().withState(DeliveryMachine.step(m.get().state(), event)));
            }
        }
    }

    // ---- reading, the outbox and housekeeping -----------------------------------------------

    /**
     * A conversation's messages, in this device's order: the order it sent or received them.
     *
     * @param matchId the conversation
     * @return the messages
     * @throws StoreException on engine failure
     */
    public List<ChatRecords.Message> messages(final byte[] matchId) throws StoreException {
        final List<ChatRecords.Message> out = new ArrayList<>();
        for (final byte[] row : store.findByNaturalKey(RecordKind.CHAT_MESSAGE, matchId)) {
            out.add(ChatRecords.Message.decode(row));
        }
        out.sort(Comparator.comparingLong(ChatRecords.Message::sequence));
        return out;
    }

    /**
     * Every queued send, first in first out: what the transport must deliver.
     *
     * @return the outbox
     * @throws StoreException on engine failure
     */
    public List<ChatRecords.OutboxItem> pendingOutbox() throws StoreException {
        final List<ChatRecords.OutboxItem> out = new ArrayList<>();
        for (final LocalStore.Entry e : store.allOf(RecordKind.OUTBOX_ITEM)) {
            out.add(ChatRecords.OutboxItem.decode(e.content()));
        }
        out.sort(Comparator.comparingLong(ChatRecords.OutboxItem::sequence));
        return out;
    }

    /**
     * Records that a transport accepted a queued send: one tick, and the
     * outbox item is done. Delivered and read come only from the peer's
     * receipts, never from here (owner ruling 2026-11-21).
     *
     * @param messageId the idempotency key
     * @throws StoreException on engine failure
     */
    public void markAccepted(final byte[] messageId) throws StoreException {
        finishSend(messageId, DeliveryMachine.Event.TRANSPORT_ACCEPTED);
    }

    /**
     * Records that a queued send can never go as it is (the relay refused it for good): the red mark,
     * and the outbox item is done. A retry is a new send the user chooses.
     *
     * @param messageId the idempotency key
     * @throws StoreException on engine failure
     */
    public void markFailed(final byte[] messageId) throws StoreException {
        finishSend(messageId, DeliveryMachine.Event.ATTEMPTS_EXHAUSTED);
    }

    /** A queued send is done, with the transport's verdict; the outbox item goes in the same transaction. */
    private void finishSend(final byte[] messageId, final DeliveryMachine.Event verdict) throws StoreException {
        store.transaction(() -> {
            final Optional<ChatRecords.Message> m = message(messageId);
            if (m.isPresent() && m.get().outgoing()) {
                final DeliveryMachine.State sending = DeliveryMachine.step(m.get().state(), DeliveryMachine.Event.SEND_STARTED);
                putMessage(m.get().withState(DeliveryMachine.step(sending, verdict)));
            }
            store.delete(RecordKind.OUTBOX_ITEM, ChatRecords.rowId(messageId));
            return null;
        });
    }

    /**
     * Removes one message from this device only (SRS-RMT-010); the peer is told nothing. Refused while it
     * waits in the outbox: its sealed envelope would still go, and the person must not think it gone.
     *
     * @param messageId the message
     * @return true when removed; false when still waiting to go out, or unknown
     * @throws StoreException on engine failure
     */
    public boolean deleteForMe(final byte[] messageId) throws StoreException {
        return store.transaction(() -> {
            final Optional<ChatRecords.Message> m = message(messageId);
            if (m.isEmpty() || store.get(RecordKind.OUTBOX_ITEM, ChatRecords.rowId(messageId)).isPresent()) {
                return false;
            }
            store.delete(RecordKind.CHAT_MESSAGE, ChatRecords.rowId(messageId));
            return true;
        });
    }

    /**
     * Forgets displayed ids whose dedup window has passed (TRS-LOC-005).
     *
     * @return how many were forgotten
     * @throws StoreException on engine failure
     */
    public int purgeExpiredDedup() throws StoreException {
        return ChatSessions.purgeExpiredDedup(store, clock.nowUtcMillis());
    }
}

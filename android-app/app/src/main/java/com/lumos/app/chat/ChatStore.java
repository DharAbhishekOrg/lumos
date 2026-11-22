/*
 * Lumos - android-app/app / Conversations, messages, the outbox and ratchet sessions, each change one transaction.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-009, SRS-RMT-011, SRS-RMT-017, TRS-LOC-004, TRS-LOC-005, TRS-PAT-004, TRS-CRY-005
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
 */

package com.lumos.app.chat;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
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

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * MILLIS_PER_SECOND;

    private final LocalStore store;
    private final RemoteSession.Identity own;
    private final LocalStore.Clock clock;
    private final DoubleRatchet.KeyProvider ratchetKeys;

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

    /**
     * @param store the sealed local store
     * @param own this device's identity
     * @param clock the UTC clock
     * @param ratchetKeys fresh ratchet scalars from the platform CSPRNG
     */
    public ChatStore(final LocalStore store, final RemoteSession.Identity own, final LocalStore.Clock clock,
            final DoubleRatchet.KeyProvider ratchetKeys) {
        this.store = store;
        this.own = own;
        this.clock = clock;
        this.ratchetKeys = ratchetKeys;
    }

    private long nowSeconds() {
        return clock.nowUtcMillis() / MILLIS_PER_SECOND;
    }

    // ---- prekeys and conversations ----------------------------------------------------------

    /**
     * Stores a private prekey this device published.
     *
     * @param secret the prekey
     * @throws StoreException on engine failure
     */
    public void putPrekeySecret(final ChatRecords.PrekeySecret secret) throws StoreException {
        store.putByNaturalKey(RecordKind.PREKEY_SECRET, secret.encode(),
                ChatRecords.PrekeySecret.naturalKey(secret.oneTime(), secret.id()));
    }

    private Optional<LocalStore.Entry> prekey(final boolean oneTime, final long id) throws StoreException {
        return store.findEntriesByNaturalKey(RecordKind.PREKEY_SECRET, ChatRecords.PrekeySecret.naturalKey(oneTime, id))
                .stream().findFirst();
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

    private Optional<ChatRecords.Conversation> conversation(final byte[] matchId) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.CONVERSATION, matchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(ChatRecords.Conversation.decode(rows.get(0)));
    }

    private Optional<ChatRecords.Session> session(final byte[] matchId) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.RATCHET_SESSION, matchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(ChatRecords.Session.decode(rows.get(0)));
    }

    private void saveSession(final byte[] matchId, final DoubleRatchet r, final byte[] prekeyFields)
            throws StoreException {
        store.putByNaturalKey(RecordKind.RATCHET_SESSION, new ChatRecords.Session(r.exportState(), prekeyFields).encode(),
                matchId);
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
        final byte[] prekey = encodePrekey(started.prekey());
        store.transaction(() -> {
            acceptConversation(matchId, peerIdentityEd);
            saveSession(matchId, started.ratchet(), prekey);
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
        return session(matchId).isPresent();
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
        store.transaction(() -> {
            final ChatRecords.Session s = session(matchId).orElseThrow(
                    () -> new StoreException(StoreException.Reason.ENGINE, "no session with this match"));
            final Optional<ChatRecords.Message> target = targetFor(matchId, event, true);
            final DoubleRatchet r = restore(s);
            final byte[] envelope;
            try {
                envelope = RemoteSession.seal(r, new RemoteText.Plaintext(id, event), decodePrekey(s.prekeyFields()),
                        nowSeconds());
            } catch (final Frame.WireException e) {
                sealFailure[0] = e;
                throw new StoreException(StoreException.Reason.ENGINE, "seal failed");
            }
            saveSession(matchId, r, s.prekeyFields());
            applyOwn(matchId, id, event, target);
            final long order = pendingOutbox().stream().mapToLong(ChatRecords.OutboxItem::sequence).max().orElse(0) + 1;
            store.put(RecordKind.OUTBOX_ITEM, ChatRecords.rowId(id),
                    new ChatRecords.OutboxItem(id, matchId, envelope, clock.nowUtcMillis(), order, 0).encode(), null);
            return null;
        });
        if (sealFailure[0] != null) {
            throw sealFailure[0];
        }
        return id;
    }

    private Optional<ChatRecords.Message> targetFor(final byte[] matchId, final RemoteText.TextEvent event,
            final boolean mine) throws StoreException {
        if (event.kind() == TextEventKind.TEXT) {
            return Optional.empty();
        }
        final Optional<ChatRecords.Message> t = message(event.target());
        final boolean authorOnly = event.kind() != TextEventKind.REACTION;
        final boolean valid = t.isPresent() && Arrays.equals(t.get().matchId(), matchId) && !t.get().deleted()
                && (!authorOnly || t.get().outgoing() == mine);
        if (!valid && mine) {
            throw new StoreException(StoreException.Reason.ENGINE, "that message cannot be the target of this event");
        }
        return valid ? t : Optional.empty();
    }

    private void applyOwn(final byte[] matchId, final byte[] id, final RemoteText.TextEvent event,
            final Optional<ChatRecords.Message> target) throws StoreException {
        if (event.kind() == TextEventKind.TEXT) {
            putMessage(new ChatRecords.Message(id, matchId, true, event.body(), clock.nowUtcMillis(), nextSequence(matchId),
                    DeliveryMachine.State.QUEUED, false, false, new byte[0], new byte[0]));
            return;
        }
        putMessage(applied(target.orElseThrow(), event, true));
    }

    private static ChatRecords.Message applied(final ChatRecords.Message t, final RemoteText.TextEvent event,
            final boolean mine) {
        switch (event.kind()) {
            case EDIT:
                return t.withEdit(event.body());
            case DELETE_FOR_EVERYONE:
                return t.withDeleted();
            default:
                return t.withReaction(mine, event.body());
        }
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
        final ChatRecords.Conversation conv = conversation(matchId).orElseThrow(
                () -> new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "no such conversation"));
        final Optional<ChatRecords.Session> existing = session(matchId);
        final DoubleRatchet r;
        byte[] prekeyFields = new byte[0];
        if (existing.isPresent()) {
            r = restore(existing.get());
            // The responder never carries PREKEY fields; the initiator drops
            // them once a NORMAL envelope from the peer opens.
            prekeyFields = envelope.prekey() == null ? new byte[0] : existing.get().prekeyFields();
        } else {
            r = respond(conv, envelope);
        }
        final byte[] plaintext = r.decrypt(envelope.header(), envelope.ciphertext(), nowSeconds(), ratchetKeys);
        if (plaintext.length < RemoteText.MESSAGE_ID_SIZE
                || !Arrays.equals(Arrays.copyOf(plaintext, RemoteText.MESSAGE_ID_SIZE), outerId)) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "authenticated id differs");
        }
        saveSession(matchId, r, prekeyFields);
        if (existing.isEmpty() && envelope.prekey().opkId() != LumosConstants.X3DH_OPK_ID_NONE_DEFAULT) {
            store.delete(RecordKind.PREKEY_SECRET, prekey(true, envelope.prekey().opkId()).orElseThrow().id());
        }
        store.put(RecordKind.MESSAGE_DEDUP, ChatRecords.rowId(outerId),
                ChatRecords.encodeDedup(clock.nowUtcMillis() + LumosConstants.DEDUP_CACHE_DAYS * MILLIS_PER_DAY), null);
        final RemoteText.Plaintext p;
        try {
            p = RemoteText.decodePlaintext(plaintext);
        } catch (final Frame.WireException e) {
            if (LumosErrors.ERR_TEXT_UNKNOWN_EVENT_KIND.equals(e.code())) {
                return new Received(Outcome.IGNORED, ""); // consumed, safely ignored (SRS-RMT-011)
            }
            throw e;
        }
        return applyPeer(matchId, outerId, p.event());
    }

    private DoubleRatchet respond(final ChatRecords.Conversation conv, final RemoteText.Envelope envelope)
            throws Frame.WireException, StoreException {
        final RemoteText.PrekeyFields p = envelope.prekey();
        if (p == null) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "no session for a NORMAL envelope");
        }
        final Optional<LocalStore.Entry> spk = prekey(false, p.spkId());
        if (spk.isEmpty()) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_PREKEY_SIGNATURE_INVALID, "signed prekey unknown");
        }
        byte[] opkPriv = null;
        if (p.opkId() != LumosConstants.X3DH_OPK_ID_NONE_DEFAULT) {
            final Optional<LocalStore.Entry> opk = prekey(true, p.opkId());
            if (opk.isPresent()) {
                opkPriv = ChatRecords.PrekeySecret.decode(opk.get().content()).privateKey();
            }
        }
        final RemoteSession.ResponderKeys keys = new RemoteSession.ResponderKeys(
                ChatRecords.PrekeySecret.decode(spk.get().content()).privateKey(), p.spkId(), opkPriv, p.opkId());
        return RemoteSession.respond(own, keys, conv.peerIdentityEd(), p, conv.matchId());
    }

    private Received applyPeer(final byte[] matchId, final byte[] id, final RemoteText.TextEvent event)
            throws StoreException {
        if (event.kind() == TextEventKind.TEXT) {
            putMessage(new ChatRecords.Message(id, matchId, false, event.body(), clock.nowUtcMillis(), nextSequence(matchId),
                    DeliveryMachine.State.DELIVERED, false, false, new byte[0], new byte[0]));
            return new Received(Outcome.DISPLAYED, "");
        }
        final Optional<ChatRecords.Message> target = targetFor(matchId, event, false);
        if (target.isEmpty()) {
            return new Received(Outcome.IGNORED, "");
        }
        putMessage(applied(target.get(), event, false));
        return new Received(Outcome.APPLIED, "");
    }

    // ---- reading, the outbox and housekeeping -----------------------------------------------

    /**
     * A conversation's messages, in this device's order: the order it sent
     * or received them.
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
        store.transaction(() -> {
            final Optional<ChatRecords.Message> m = message(messageId);
            if (m.isPresent() && m.get().outgoing()) {
                final DeliveryMachine.State sending = DeliveryMachine.step(m.get().state(), DeliveryMachine.Event.SEND_STARTED);
                putMessage(m.get().withState(DeliveryMachine.step(sending, DeliveryMachine.Event.TRANSPORT_ACCEPTED)));
            }
            store.delete(RecordKind.OUTBOX_ITEM, ChatRecords.rowId(messageId));
            return null;
        });
    }

    /**
     * Forgets displayed ids whose dedup window has passed (TRS-LOC-005).
     *
     * @return how many were forgotten
     * @throws StoreException on engine failure
     */
    public int purgeExpiredDedup() throws StoreException {
        final long now = clock.nowUtcMillis();
        int n = 0;
        for (final LocalStore.Entry e : store.allOf(RecordKind.MESSAGE_DEDUP)) {
            if (ChatRecords.decodeDedup(e.content()) < now) {
                store.delete(RecordKind.MESSAGE_DEDUP, e.id());
                n++;
            }
        }
        return n;
    }

    // ---- encodings of session pieces --------------------------------------------------------

    private static DoubleRatchet restore(final ChatRecords.Session s) throws StoreException {
        try {
            return DoubleRatchet.restoreState(s.ratchetState(), DoubleRatchet.Bounds.defaults());
        } catch (final Frame.WireException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "stored ratchet state is unreadable");
        }
    }

    private static byte[] encodePrekey(final RemoteText.PrekeyFields p) {
        // A PREKEY envelope with an empty ratchet header region is never sent;
        // only its fields are kept, re-attached to every envelope until the
        // peer replies.
        final byte[] env = new RemoteText.Envelope(p, new DoubleRatchet.Header(new byte[32], 0, 0), new byte[1]).encode();
        return Arrays.copyOfRange(env, 1, env.length - DoubleRatchet.HEADER_SIZE - 1);
    }

    private static RemoteText.PrekeyFields decodePrekey(final byte[] fields) throws StoreException {
        if (fields.length == 0) {
            return null;
        }
        try {
            final byte[] env = new byte[1 + fields.length + DoubleRatchet.HEADER_SIZE + 1];
            env[0] = (byte) RemoteText.FORMAT_PREKEY;
            System.arraycopy(fields, 0, env, 1, fields.length);
            return RemoteText.decodeEnvelope(env).prekey();
        } catch (final Frame.WireException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "stored prekey fields are unreadable");
        }
    }
}

/*
 * Lumos - android-app/app / Ratchet sessions at rest: load, seal, open, save - inside the caller's transaction.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-007, SRS-RMT-017, TRS-CRY-005, TRS-LOC-004
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: Split from ChatStore (WP-05 S7e2) so text and receipts share one
 * path and ChatStore stays inside the TRS-CODE-004 class budget. Nothing here
 * opens a transaction: every method is called inside ChatStore's, so the
 * advanced ratchet, the consumed one-time prekey, the dedup entry and
 * whatever the plaintext changes commit together or not at all
 * (SRS-RMT-017). A session is restored from the store for every operation
 * and saved only on success, so a failed decrypt is rolled back by throwing
 * the in-memory ratchet away.
 */

package com.lumos.app.chat;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The chat's ratchet sessions, as stored. */
final class ChatSessions {

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * MILLIS_PER_SECOND;

    private final LocalStore store;
    private final RemoteSession.Identity own;
    private final LocalStore.Clock clock;
    private final DoubleRatchet.KeyProvider ratchetKeys;

    /** Seals a plaintext under a restored ratchet and its PREKEY fields. */
    interface Sealer {
        byte[] seal(DoubleRatchet ratchet, RemoteText.PrekeyFields prekey) throws Frame.WireException;
    }

    private ChatSessions(final LocalStore store, final RemoteSession.Identity own, final LocalStore.Clock clock,
            final DoubleRatchet.KeyProvider ratchetKeys) {
        this.store = store;
        this.own = own;
        this.clock = clock;
        this.ratchetKeys = ratchetKeys;
    }

    static ChatSessions over(final LocalStore store, final RemoteSession.Identity own, final LocalStore.Clock clock,
            final DoubleRatchet.KeyProvider ratchetKeys) {
        return new ChatSessions(store, own, clock, ratchetKeys);
    }

    long nowSeconds() {
        return clock.nowUtcMillis() / MILLIS_PER_SECOND;
    }

    Optional<ChatRecords.Session> session(final byte[] matchId) throws StoreException {
        final List<byte[]> rows = store.findByNaturalKey(RecordKind.RATCHET_SESSION, matchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(ChatRecords.Session.decode(rows.get(0)));
    }

    void save(final byte[] matchId, final DoubleRatchet r, final byte[] prekeyFields) throws StoreException {
        store.putByNaturalKey(RecordKind.RATCHET_SESSION, new ChatRecords.Session(r.exportState(), prekeyFields).encode(),
                matchId);
    }

    Optional<LocalStore.Entry> prekey(final boolean oneTime, final long id) throws StoreException {
        return store.findEntriesByNaturalKey(RecordKind.PREKEY_SECRET, PrekeySecret.naturalKey(oneTime, id))
                .stream().findFirst();
    }

    /**
     * Seals with the stored session and saves the advanced ratchet.
     *
     * @param matchId the conversation
     * @param sealer what to seal
     * @param sealFailure where a sealing failure is reported, since the transaction body cannot throw it
     * @return the envelope
     * @throws StoreException with no session, on a sealing failure, or on engine failure
     */
    byte[] seal(final byte[] matchId, final Sealer sealer, final Frame.WireException[] sealFailure)
            throws StoreException {
        final ChatRecords.Session s = session(matchId).orElseThrow(
                () -> new StoreException(StoreException.Reason.ENGINE, "no session with this match"));
        final DoubleRatchet r = restore(s);
        final byte[] envelope;
        try {
            envelope = sealer.seal(r, decodePrekey(s.prekeyFields()));
        } catch (final Frame.WireException e) {
            sealFailure[0] = e;
            throw new StoreException(StoreException.Reason.ENGINE, "seal failed");
        }
        save(matchId, r, s.prekeyFields());
        return envelope;
    }

    /**
     * Opens one envelope from the conversation's peer: restores the session
     * or responds to a PREKEY envelope, decrypts, checks the authenticated id
     * against the delivered one, saves the ratchet, deletes a consumed
     * one-time prekey and records the id as seen.
     *
     * @param conv the conversation
     * @param outerId the id the relay delivered it under
     * @param envelope the envelope
     * @return the plaintext, whose first 16 bytes are outerId
     * @throws Frame.WireException on any refusal
     * @throws StoreException on engine failure
     */
    byte[] open(final ChatRecords.Conversation conv, final byte[] outerId, final RemoteText.Envelope envelope)
            throws Frame.WireException, StoreException {
        final byte[] matchId = conv.matchId();
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
        save(matchId, r, prekeyFields);
        if (!existing.isPresent() && envelope.prekey().opkId() != LumosConstants.X3DH_OPK_ID_NONE_DEFAULT) {
            store.delete(RecordKind.PREKEY_SECRET, prekey(true, envelope.prekey().opkId()).orElseThrow().id());
        }
        store.put(RecordKind.MESSAGE_DEDUP, ChatRecords.rowId(outerId),
                ChatRecords.encodeDedup(clock.nowUtcMillis() + LumosConstants.DEDUP_CACHE_DAYS * MILLIS_PER_DAY), null);
        return plaintext;
    }

    private DoubleRatchet respond(final ChatRecords.Conversation conv, final RemoteText.Envelope envelope)
            throws Frame.WireException, StoreException {
        final RemoteText.PrekeyFields p = envelope.prekey();
        if (p == null) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "no session for a NORMAL envelope");
        }
        final Optional<LocalStore.Entry> spk = prekey(false, p.spkId());
        if (!spk.isPresent()) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_PREKEY_SIGNATURE_INVALID, "signed prekey unknown");
        }
        byte[] opkPriv = null;
        if (p.opkId() != LumosConstants.X3DH_OPK_ID_NONE_DEFAULT) {
            final Optional<LocalStore.Entry> opk = prekey(true, p.opkId());
            if (opk.isPresent()) {
                opkPriv = PrekeySecret.decode(opk.get().content()).privateKey();
            }
        }
        final RemoteSession.ResponderKeys keys = new RemoteSession.ResponderKeys(
                PrekeySecret.decode(spk.get().content()).privateKey(), p.spkId(), opkPriv, p.opkId());
        return RemoteSession.respond(own, keys, conv.peerIdentityEd(), p, conv.matchId());
    }

    static DoubleRatchet restore(final ChatRecords.Session s) throws StoreException {
        try {
            return DoubleRatchet.restoreState(s.ratchetState(), DoubleRatchet.Bounds.defaults());
        } catch (final Frame.WireException e) {
            throw new StoreException(StoreException.Reason.ENGINE, "stored ratchet state is unreadable");
        }
    }

    static byte[] encodePrekey(final RemoteText.PrekeyFields p) {
        // A PREKEY envelope with an empty ratchet header region is never sent;
        // only its fields are kept, re-attached to every envelope until the
        // peer replies.
        final byte[] env = new RemoteText.Envelope(p, new DoubleRatchet.Header(new byte[32], 0, 0), new byte[1]).encode();
        return Arrays.copyOfRange(env, 1, env.length - DoubleRatchet.HEADER_SIZE - 1);
    }

    static RemoteText.PrekeyFields decodePrekey(final byte[] fields) throws StoreException {
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

/*
 * Lumos - android-app/app / Signed and one-time prekeys: minted, stored, then published.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-005, TRS-BCK-005, SRS-RMT-001, SRS-RMT-007
 * ADRs: ADR-001, ADR-008
 * Threading: serial-executor
 * Notes: The order is the safety property: a batch's private halves and the
 * id counter are written in ONE transaction before anything is uploaded, so a
 * peer can never claim a prekey this device cannot open. If the upload then
 * fails - or succeeds and the answer is lost - the next attempt mints a fresh
 * batch with fresh ids rather than resending the old one: the old private
 * halves stay, because the registry may hold their publics, and an id is
 * never reused, so an old PREKEY envelope can never meet a different key
 * under its id (TRS-CRY-005). Generation only happens after registration
 * succeeded, i.e. when the network was there a moment ago, so a device that
 * is simply offline mints nothing.
 *
 * The counters live in their own RELAY_ACCOUNT row that forgetting a
 * registration leaves alone, so ids stay monotonic across registrations.
 * A fresh registration is a fresh, empty prekey store at the registry, so the
 * bundle is republished whenever the registration it was published under is
 * not the current one. Signed-prekey rotation is not here yet (the register
 * has no rotation period); a new bundle is signed per registration.
 */

package com.lumos.app.relay;

import com.lumos.app.chat.ChatStore;
import com.lumos.app.chat.PrekeySecret;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.wire.LumosCrypto;
import com.lumos.protocol.x3dh.X3dh;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Publishes and replenishes this device's prekeys. */
public final class PrekeyPublisher {

    /** The counters' row in RELAY_ACCOUNT. */
    static final String ROW = "prekeys";
    private static final int FORMAT = 1;
    private static final int STATE_BYTES = 1 + Integer.BYTES + Integer.BYTES + 1 + RelayClient.ROUTING_ID_SIZE;
    private static final long UINT32_MAX = 0xFFFF_FFFFL;

    /** Signs with the identity's Ed25519 private key (libsodium on Android). */
    public interface Signer {
        /**
         * Signs.
         *
         * @param message the exact bytes
         * @return the 64-byte signature
         * @throws GeneralSecurityException on backend failure
         */
        byte[] sign(byte[] message) throws GeneralSecurityException;
    }

    private final LocalStore store;
    private final ChatStore chat;
    private final RemoteSession.Identity own;
    private final Signer signer;
    private final SecureRandom random;

    private PrekeyPublisher(final LocalStore store, final ChatStore chat, final RemoteSession.Identity own,
            final Signer signer, final SecureRandom random) {
        this.store = store;
        this.chat = chat;
        this.own = own;
        this.signer = signer;
        this.random = random;
    }

    /**
     * Binds the publisher.
     *
     * @param store the sealed local store
     * @param chat the chat store that keeps prekey secrets
     * @param own this device's identity
     * @param signer the identity's signing key
     * @param random the platform CSPRNG
     * @return the publisher
     */
    public static PrekeyPublisher over(final LocalStore store, final ChatStore chat, final RemoteSession.Identity own,
            final Signer signer, final SecureRandom random) {
        return new PrekeyPublisher(store, chat, own, signer, random);
    }

    /**
     * The counters: last signed-prekey id, next one-time id, and the
     * registration the current bundle was published under.
     *
     * @param signedId the last signed-prekey id used, 0 for none
     * @param nextOneTime the next one-time id to use
     * @param publishedFor the routing id the bundle was published under, or empty
     */
    record State(long signedId, long nextOneTime, byte[] publishedFor) {

        State {
            publishedFor = publishedFor.clone();
        }

        @Override
        public byte[] publishedFor() {
            return publishedFor.clone();
        }

        byte[] encode() {
            return ByteBuffer.allocate(STATE_BYTES).put((byte) FORMAT).putInt((int) signedId)
                    .putInt((int) nextOneTime).put((byte) publishedFor.length)
                    .put(Arrays.copyOf(publishedFor, RelayClient.ROUTING_ID_SIZE)).array();
        }

        static State decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = ByteBuffer.wrap(b);
                if (b.length != STATE_BYTES || in.get() != FORMAT) {
                    throw unreadable();
                }
                final long signed = in.getInt() & UINT32_MAX;
                final long next = in.getInt() & UINT32_MAX;
                final int n = in.get();
                final byte[] rid = new byte[RelayClient.ROUTING_ID_SIZE];
                in.get(rid);
                if (n != 0 && n != RelayClient.ROUTING_ID_SIZE) {
                    throw unreadable();
                }
                return new State(signed, next, n == 0 ? new byte[0] : rid);
            } catch (final BufferUnderflowException e) {
                throw unreadable();
            }
        }
    }

    private static StoreException unreadable() {
        return new StoreException(StoreException.Reason.ENGINE, "prekey counters are unreadable");
    }

    State state() throws StoreException {
        final Optional<byte[]> row = store.get(RecordKind.RELAY_ACCOUNT, ROW);
        return row.isPresent() ? State.decode(row.get()) : new State(0, 1, new byte[0]);
    }

    /**
     * Makes sure the registry holds a current bundle and enough one-time
     * prekeys for this registration.
     *
     * @param client the authenticated client
     * @param registration the current registration
     * @return how many one-time prekeys were published by this call
     * @throws RelayException when the registry could not be reached or refused
     * @throws StoreException on engine failure
     * @throws GeneralSecurityException when signing or key derivation failed
     */
    public int ensure(final RelayClient client, final RelayTypes.Registration registration)
            throws RelayException, StoreException, GeneralSecurityException {
        final State s = state();
        if (!Arrays.equals(s.publishedFor(), registration.routingId())) {
            final Batch b = mint(s, true, LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT);
            client.publishPrekeys(b.bundle, b.oneTime);
            final State after = state();
            store.put(RecordKind.RELAY_ACCOUNT, ROW,
                    new State(after.signedId(), after.nextOneTime(), registration.routingId()).encode(), null);
            return b.oneTime.size();
        }
        final RelayTypes.PrekeyStatus status = client.prekeyStatus();
        if (!status.needsReplenish()) {
            return 0;
        }
        final int room = (int) Math.max(0, LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT - status.remaining());
        if (room == 0) {
            return 0;
        }
        final Batch b = mint(s, false, room);
        client.publishPrekeys(null, b.oneTime);
        return b.oneTime.size();
    }

    /** One minted batch: the public halves to upload. */
    private static final class Batch {
        private final X3dh.Bundle bundle;
        private final List<RelayTypes.OneTimeKey> oneTime;

        Batch(final X3dh.Bundle bundle, final List<RelayTypes.OneTimeKey> oneTime) {
            this.bundle = bundle;
            this.oneTime = oneTime;
        }
    }

    /**
     * Mints keys, storing every private half and the advanced counters in one
     * transaction, and returns the public halves.
     */
    private Batch mint(final State s, final boolean withBundle, final int count)
            throws StoreException, GeneralSecurityException {
        final long signedId = withBundle ? s.signedId() + 1 : s.signedId();
        if (signedId > UINT32_MAX || s.nextOneTime() + count > UINT32_MAX) {
            throw new GeneralSecurityException("prekey ids exhausted");
        }
        final List<PrekeySecret> secrets = new ArrayList<>();
        final List<RelayTypes.OneTimeKey> publics = new ArrayList<>();
        X3dh.Bundle bundle = null;
        if (withBundle) {
            final byte[] spkPriv = scalar();
            secrets.add(new PrekeySecret(false, signedId, spkPriv));
            bundle = signedBundle(signedId, LumosCrypto.x25519PublicFromPrivate(spkPriv));
            Arrays.fill(spkPriv, (byte) 0);
        }
        for (int k = 0; k < count; k++) {
            final long id = s.nextOneTime() + k;
            final byte[] priv = scalar();
            secrets.add(new PrekeySecret(true, id, priv));
            publics.add(new RelayTypes.OneTimeKey(id, LumosCrypto.x25519PublicFromPrivate(priv)));
            Arrays.fill(priv, (byte) 0);
        }
        final State next = new State(signedId, s.nextOneTime() + count, s.publishedFor());
        store.transaction(() -> {
            for (final PrekeySecret p : secrets) {
                chat.putPrekeySecret(p);
            }
            store.put(RecordKind.RELAY_ACCOUNT, ROW, next.encode(), null);
            return null;
        });
        return new Batch(bundle, publics);
    }

    private byte[] scalar() {
        final byte[] b = new byte[X3dh.SK_SIZE];
        random.nextBytes(b);
        return b;
    }

    private X3dh.Bundle signedBundle(final long spkId, final byte[] spkPub) throws GeneralSecurityException {
        final byte[] signed = LumosCrypto.concat(
                LumosConstants.X3DH_PREKEY_SIGNING_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                ByteBuffer.allocate(Integer.BYTES).putInt((int) spkId).array(), spkPub);
        return new X3dh.Bundle(own.identityEd(), LumosCrypto.x25519PublicFromPrivate(own.identityDhPriv()),
                own.identityDhSig(), spkId, spkPub, signer.sign(signed), 0, null);
    }
}

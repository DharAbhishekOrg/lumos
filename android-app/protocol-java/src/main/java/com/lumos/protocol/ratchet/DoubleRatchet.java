/*
 * Lumos - android-app/protocol-java / Double Ratchet session state.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-002, TRS-CRY-006, TRS-CRY-007, TRS-CRY-011, TRS-CRY-013
 * ADRs: ADR-001, ADR-008
 * Threading: serial-executor
 * Notes: A decision-for-decision port of protocol/crypto/ratchet/ratchet.go,
 * proven against test-vectors/crypto/ratchet_sequences.json. State is exactly
 * the TRS-CRY-002 inventory: root key, DH pairs, sending/receiving chain keys,
 * Ns, Nr, PN and bounded skipped keys. Message keys are zeroed after one use
 * (TRS-CRY-006); skipped keys are bounded by MAX_SKIP per chain, a per-session
 * cap and a TTL pruned against the CALLER's clock (TRS-CRY-007), so the
 * machine stays deterministic. Every failure carries a stable registry code
 * (TRS-CRY-013). Not safe for concurrent use: one serial executor per session.
 *
 * exportState/restoreState (WP-05 S7b) serialise the whole TRS-CRY-002 state,
 * skipped keys included, so a session survives process death (SRS-RMT-017).
 * The bytes ARE key material: the caller stores them sealed (the app's
 * LocalStore) and writes them in the same transaction as the message they
 * sealed or opened, so a restart can never return to a state that still
 * holds a key already used. A failed decrypt changes this object, so callers
 * persist only after success and otherwise discard it and restore again.
 */

package com.lumos.protocol.ratchet;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** One side of a Double Ratchet session. */
public final class DoubleRatchet {

    /** The fixed header length (register RATCHET_HEADER_LAYOUT). */
    public static final int HEADER_SIZE = LumosConstants.RATCHET_HEADER_LAYOUT_TOTAL_LEN;

    private static final int KEY_LEN = 32;
    private static final long SECONDS_PER_DAY = 24L * 60 * 60;

    /**
     * The authenticated header: sender's ratchet public key, previous chain
     * length and message number.
     *
     * @param dh the sender's current 32-byte ratchet public key
     * @param pn the previous sending chain's length
     * @param n this message's number in its chain
     */
    public record Header(byte[] dh, long pn, long n) {

        /**
         * Copies the key in, so a caller's array never aliases the header.
         *
         * @param dh the sender's ratchet public key
         * @param pn the previous chain length
         * @param n the message number
         */
        public Header {
            dh = dh.clone();
        }

        /** @return a copy of the sender's ratchet public key */
        @Override
        public byte[] dh() {
            return dh.clone();
        }

        /** @return the canonical dh(32) | pn(4,BE) | n(4,BE) layout */
        public byte[] bytes() {
            return ByteBuffer.allocate(HEADER_SIZE).put(dh).putInt((int) pn).putInt((int) n).array();
        }

        /**
         * @param b exactly 40 bytes
         * @return the parsed header
         * @throws Frame.WireException ERR_PAYLOAD_MALFORMED on any other length
         */
        public static Header parse(final byte[] b) throws Frame.WireException {
            if (b.length != HEADER_SIZE) {
                throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "ratchet header must be 40 bytes");
            }
            final ByteBuffer buf = ByteBuffer.wrap(b);
            final byte[] dh = new byte[KEY_LEN];
            buf.get(dh);
            return new Header(dh, Integer.toUnsignedLong(buf.getInt()), Integer.toUnsignedLong(buf.getInt()));
        }
    }

    /**
     * An encrypted message.
     *
     * @param header the authenticated header
     * @param ciphertext the sealed body with its tag
     */
    public record Sealed(Header header, byte[] ciphertext) {

        /**
         * Copies the body in.
         *
         * @param header the authenticated header
         * @param ciphertext the sealed body
         */
        public Sealed {
            ciphertext = ciphertext.clone();
        }

        /** @return a copy of the sealed body */
        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    /** Supplies fresh ratchet private scalars: the platform CSPRNG in production. */
    @FunctionalInterface
    public interface KeyProvider {
        /**
         * @return a fresh raw 32-byte X25519 private scalar
         * @throws GeneralSecurityException when no key can be produced
         */
        byte[] generate() throws GeneralSecurityException;
    }

    /**
     * The register's denial-of-service limits; a zero means the register
     * default. Tests may narrow them, never widen.
     *
     * @param maxSkip skipped keys allowed within one chain
     * @param skippedCap skipped keys allowed across the session
     * @param skippedTtlSec how long a skipped key lives, in seconds
     */
    public record Bounds(int maxSkip, int skippedCap, long skippedTtlSec) {

        /** @return the register defaults */
        public static Bounds defaults() {
            return new Bounds(0, 0, 0).withDefaults();
        }

        Bounds withDefaults() {
            return new Bounds(
                    maxSkip == 0 ? LumosConstants.MAX_SKIP_PER_CHAIN_DEFAULT : maxSkip,
                    skippedCap == 0 ? LumosConstants.SKIPPED_KEYS_PER_SESSION_CAP_DEFAULT : skippedCap,
                    skippedTtlSec == 0
                            ? LumosConstants.SKIPPED_KEY_TTL_DAYS_DEFAULT * SECONDS_PER_DAY : skippedTtlSec);
        }
    }

    /**
     * A stored out-of-order message key.
     *
     * @param mk the message key, zeroed when used or pruned
     * @param storedAt when it was stored, caller's clock
     */
    private record Skipped(byte[] mk, long storedAt) { }

    private byte[] dhsPriv;
    private byte[] dhsPub;
    private byte[] dhr;
    private byte[] rk;
    private byte[] cks;
    private byte[] ckr;
    private long ns;
    private long nr;
    private long pn;
    private final byte[] ad;
    private final Map<String, Skipped> skip = new HashMap<>();
    private final Bounds bounds;

    /** The exported state's format version. */
    private static final int STATE_FORMAT = 1;
    private static final int U32 = 4;
    private static final long U32_MASK = 0xFFFFFFFFL;

    private DoubleRatchet(final byte[] sk, final byte[] ad, final byte[] ownPriv, final Bounds bounds)
            throws GeneralSecurityException {
        this.rk = sk.clone();
        this.ad = ad.clone();
        this.dhsPriv = ownPriv.clone();
        this.dhsPub = LumosCrypto.x25519PublicFromPrivate(ownPriv);
        this.bounds = bounds.withDefaults();
    }

    /**
     * Builds the sender-first side from X3DH's SK.
     *
     * @param sk the 32-byte shared secret from X3DH
     * @param ad the session associated data
     * @param remoteRatchetPub the responder's signed ratchet public key
     * @param ownRatchetPriv this side's first ratchet scalar
     * @param bounds denial-of-service limits
     * @return the initiator state, ready to encrypt
     * @throws Frame.WireException ERR_CRYPTO_AEAD_AUTH_FAILED if the agreement fails
     */
    public static DoubleRatchet initiator(final byte[] sk, final byte[] ad, final byte[] remoteRatchetPub,
            final byte[] ownRatchetPriv, final Bounds bounds) throws Frame.WireException {
        try {
            final DoubleRatchet s = new DoubleRatchet(sk, ad, ownRatchetPriv, bounds);
            s.dhr = remoteRatchetPub.clone();
            final byte[][] out = kdfRk(s.rk, s.agree());
            s.rk = out[0];
            s.cks = out[1];
            return s;
        } catch (GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "dh failed");
        }
    }

    /**
     * Builds the receiver-first side with its published ratchet key.
     *
     * @param sk the 32-byte shared secret from X3DH
     * @param ad the session associated data
     * @param ownRatchetPriv this side's published ratchet scalar
     * @param bounds denial-of-service limits
     * @return the responder state, ready to decrypt the first message
     * @throws Frame.WireException ERR_CRYPTO_AEAD_AUTH_FAILED on a malformed scalar
     */
    public static DoubleRatchet responder(final byte[] sk, final byte[] ad, final byte[] ownRatchetPriv,
            final Bounds bounds) throws Frame.WireException {
        try {
            return new DoubleRatchet(sk, ad, ownRatchetPriv, bounds);
        } catch (GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "bad ratchet key");
        }
    }

    /** @return how many out-of-order message keys are stored (TRS-CRY-006 proofs) */
    public int skippedCount() {
        return skip.size();
    }

    /**
     * Produces the next message.
     *
     * @param plaintext the message body
     * @param now the caller's clock in unix seconds, used only to prune
     * @return header and ciphertext
     * @throws Frame.WireException ERR_CRYPTO_AEAD_AUTH_FAILED with no sending chain yet
     */
    public Sealed encrypt(final byte[] plaintext, final long now) throws Frame.WireException {
        pruneExpired(now);
        if (cks == null) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "no sending chain yet");
        }
        final byte[][] step = kdfCk(cks);
        Arrays.fill(cks, (byte) 0);
        cks = step[1];
        final Header h = new Header(dhsPub, pn, ns);
        final byte[] ct = seal(step[0], h, plaintext);
        ns++;
        return new Sealed(h, ct);
    }

    /**
     * Consumes one message. A skipped-key hit consumes and deletes the stored
     * key; a new remote key turns the DH ratchet with a fresh key from kp.
     *
     * @param h the parsed header
     * @param ciphertext the sealed body
     * @param now the caller's clock in unix seconds
     * @param kp source of the next sending scalar
     * @return the plaintext
     * @throws Frame.WireException a stable crypto code; terminal for the frame
     */
    public byte[] decrypt(final Header h, final byte[] ciphertext, final long now, final KeyProvider kp)
            throws Frame.WireException {
        pruneExpired(now);
        final Skipped hit = skip.remove(skipKey(h.dh(), h.n()));
        if (hit != null) {
            return open(hit.mk(), h, ciphertext);
        }
        if (dhr == null || !Arrays.equals(h.dh(), dhr)) {
            skipMessageKeys(h.pn(), now);
            dhRatchet(h, kp);
        }
        skipMessageKeys(h.n(), now);
        final byte[][] step = kdfCk(ckr);
        Arrays.fill(ckr, (byte) 0);
        ckr = step[1];
        nr++;
        return open(step[0], h, ciphertext);
    }

    /**
     * Serialises the complete session state, skipped keys included.
     *
     * @return the state bytes; key material, to be stored sealed
     */
    public byte[] exportState() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(STATE_FORMAT);
        append(out, dhsPriv);
        writeOptional(out, dhr);
        append(out, rk);
        writeOptional(out, cks);
        writeOptional(out, ckr);
        append(out, ByteBuffer.allocate(3 * U32).putInt((int) ns).putInt((int) nr).putInt((int) pn).array());
        append(out, ByteBuffer.allocate(2).putShort((short) ad.length).array());
        append(out, ad);
        append(out, ByteBuffer.allocate(U32).putInt(skip.size()).array());
        for (final Map.Entry<String, Skipped> e : skip.entrySet()) {
            final int colon = e.getKey().indexOf(':');
            append(out, unhex(e.getKey().substring(0, colon)));
            append(out, ByteBuffer.allocate(U32 + KEY_LEN + Long.BYTES)
                    .putInt((int) Long.parseLong(e.getKey().substring(colon + 1)))
                    .put(e.getValue().mk()).putLong(e.getValue().storedAt()).array());
        }
        return out.toByteArray();
    }

    private static void writeOptional(final ByteArrayOutputStream out, final byte[] key) {
        out.write(key == null ? 0 : 1);
        if (key != null) {
            append(out, key);
        }
    }

    private static byte[] readKey(final ByteBuffer in) {
        final byte[] k = new byte[KEY_LEN];
        in.get(k);
        return k;
    }

    /** Reads the presence flag of an optional key: true when the key follows. */
    private static boolean present(final ByteBuffer in) throws Frame.WireException {
        final int flag = in.get();
        if (flag != 0 && flag != 1) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "bad ratchet state flag");
        }
        return flag == 1;
    }

    /**
     * Rebuilds a session from {@link #exportState()}.
     *
     * @param state the exported bytes
     * @param bounds denial-of-service limits (from the register, not stored)
     * @return the session, exactly as it was exported
     * @throws Frame.WireException ERR_PAYLOAD_MALFORMED when the bytes are not a state
     */
    public static DoubleRatchet restoreState(final byte[] state, final Bounds bounds) throws Frame.WireException {
        try {
            final ByteBuffer in = ByteBuffer.wrap(state);
            if (in.get() != STATE_FORMAT) {
                throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "unknown ratchet state format");
            }
            final byte[] dhsPriv = readKey(in);
            final byte[] dhr = present(in) ? readKey(in) : null;
            final byte[] rk = readKey(in);
            final byte[] cks = present(in) ? readKey(in) : null;
            final byte[] ckr = present(in) ? readKey(in) : null;
            final long ns = in.getInt() & U32_MASK;
            final long nr = in.getInt() & U32_MASK;
            final long pn = in.getInt() & U32_MASK;
            final byte[] ad = new byte[in.getShort() & 0xFFFF];
            in.get(ad);
            final DoubleRatchet s = new DoubleRatchet(rk, ad, dhsPriv, bounds);
            s.dhr = dhr;
            s.cks = cks;
            s.ckr = ckr;
            s.ns = ns;
            s.nr = nr;
            s.pn = pn;
            final long count = in.getInt() & U32_MASK;
            if (count > s.bounds.skippedCap()) {
                throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "skipped keys exceed the cap");
            }
            for (long i = 0; i < count; i++) {
                final byte[] dh = readKey(in);
                final long n = in.getInt() & U32_MASK;
                s.skip.put(skipKey(dh, n), new Skipped(readKey(in), in.getLong()));
            }
            if (in.hasRemaining()) {
                throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "trailing ratchet state bytes");
            }
            return s;
        } catch (final BufferUnderflowException | GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "ratchet state truncated or invalid");
        }
    }

    private void skipMessageKeys(final long until, final long now) throws Frame.WireException {
        if (ckr == null) {
            return;
        }
        if (until > nr && until - nr > bounds.maxSkip()) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_MAX_SKIP_EXCEEDED, "gap exceeds MAX_SKIP_PER_CHAIN");
        }
        while (nr < until) {
            if (skip.size() >= bounds.skippedCap()) {
                throw new Frame.WireException(LumosErrors.ERR_CRYPTO_SKIPPED_KEY_CAP,
                        "session skipped-key cap reached");
            }
            final byte[][] step = kdfCk(ckr);
            Arrays.fill(ckr, (byte) 0);
            ckr = step[1];
            skip.put(skipKey(dhr, nr), new Skipped(step[0], now));
            nr++;
        }
    }

    private void dhRatchet(final Header h, final KeyProvider kp) throws Frame.WireException {
        pn = ns;
        ns = 0;
        nr = 0;
        try {
            dhr = h.dh();
            byte[][] out = kdfRk(rk, agree());
            rk = out[0];
            ckr = out[1];
            Arrays.fill(dhsPriv, (byte) 0);
            dhsPriv = kp.generate();
            dhsPub = LumosCrypto.x25519PublicFromPrivate(dhsPriv);
            out = kdfRk(rk, agree());
            rk = out[0];
            cks = out[1];
        } catch (GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "dh ratchet failed");
        }
    }

    private byte[] agree() throws GeneralSecurityException {
        return LumosCrypto.x25519(LumosCrypto.x25519Private(dhsPriv), LumosCrypto.x25519Public(dhr));
    }

    private void pruneExpired(final long now) {
        final Iterator<Map.Entry<String, Skipped>> it = skip.entrySet().iterator();
        while (it.hasNext()) {
            final Skipped e = it.next().getValue();
            if (now - e.storedAt() > bounds.skippedTtlSec()) {
                Arrays.fill(e.mk(), (byte) 0);
                it.remove();
            }
        }
    }

    /** KDF_RK: HKDF-SHA-256(salt=rk, ikm=dh_out) into (rk', ck). */
    private static byte[][] kdfRk(final byte[] rk, final byte[] dhOut) {
        final byte[] okm = LumosCrypto.hkdf(dhOut, rk,
                LumosConstants.RATCHET_ROOT_KDF_INFO_VALUE.getBytes(StandardCharsets.UTF_8), 2 * KEY_LEN);
        Arrays.fill(dhOut, (byte) 0);
        final byte[][] out = {Arrays.copyOfRange(okm, 0, KEY_LEN), Arrays.copyOfRange(okm, KEY_LEN, 2 * KEY_LEN)};
        Arrays.fill(okm, (byte) 0);
        return out;
    }

    /** KDF_CK: mk = HMAC(ck, 0x01), ck' = HMAC(ck, 0x02). */
    private static byte[][] kdfCk(final byte[] ck) {
        return new byte[][] {
            LumosCrypto.hmacSha256(ck, new byte[] {(byte) LumosConstants.CHAIN_KEY_SEEDS_MESSAGE}),
            LumosCrypto.hmacSha256(ck, new byte[] {(byte) LumosConstants.CHAIN_KEY_SEEDS_NEXT}),
        };
    }

    private byte[] seal(final byte[] mk, final Header h, final byte[] plaintext) throws Frame.WireException {
        final byte[] okm = expandMessageKey(mk);
        try {
            return LumosCrypto.aeadSeal(Arrays.copyOfRange(okm, 0, KEY_LEN),
                    Arrays.copyOfRange(okm, KEY_LEN, KEY_LEN + LumosCrypto.AEAD_NONCE_SIZE),
                    plaintext, LumosCrypto.concat(ad, h.bytes()));
        } catch (GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "seal failed");
        } finally {
            Arrays.fill(okm, (byte) 0);
        }
    }

    private byte[] open(final byte[] mk, final Header h, final byte[] ciphertext) throws Frame.WireException {
        final byte[] okm = expandMessageKey(mk);
        try {
            return LumosCrypto.aeadOpen(Arrays.copyOfRange(okm, 0, KEY_LEN),
                    Arrays.copyOfRange(okm, KEY_LEN, KEY_LEN + LumosCrypto.AEAD_NONCE_SIZE),
                    ciphertext, LumosCrypto.concat(ad, h.bytes()));
        } catch (GeneralSecurityException e) {
            throw new Frame.WireException(LumosErrors.ERR_CRYPTO_AEAD_AUTH_FAILED, "open failed");
        } finally {
            Arrays.fill(okm, (byte) 0);
        }
    }

    /** The register's message-key expansion: HKDF(mk, zero salt, msg info) into key || nonce. */
    private static byte[] expandMessageKey(final byte[] mk) {
        final byte[] okm = LumosCrypto.hkdf(mk, new byte[KEY_LEN],
                LumosConstants.RATCHET_MESSAGE_KDF_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                LumosCrypto.AEAD_KEY_SIZE + LumosCrypto.AEAD_NONCE_SIZE);
        Arrays.fill(mk, (byte) 0);
        return okm;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Appends bytes. ByteArrayOutputStream.writeBytes is API 33 and this code
     * runs on Android from minSdk 26; write(b, 0, n) is the same on every level.
     */
    private static void append(final ByteArrayOutputStream out, final byte[] b) {
        out.write(b, 0, b.length);
    }

    /** Lower-case hex, by hand: java.util.HexFormat is API 34 on Android. */
    private static String hex(final byte[] b) {
        final char[] out = new char[2 * b.length];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = HEX[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[b[i] & 0xF];
        }
        return new String(out);
    }

    private static byte[] unhex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String skipKey(final byte[] dh, final long n) {
        return hex(dh) + ":" + n;
    }
}

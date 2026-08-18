/*
 * Lumos - android-app/app / The ADR-003 content-only RecoveryRecord, on Android.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001, TRS-CRY-013
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: The Android half of the format the Go package in
 * /protocol/crypto/recovery defines. Byte-for-byte identical output is a
 * requirement, not an aspiration: a record exported on a phone has to open on
 * the owner's next phone, which may be the other platform entirely. The
 * committed vectors in test-vectors/crypto/recovery_record.json are what holds
 * the two together, and RecoveryRecordInstrumentedTest consumes every one of
 * them on a real Android runtime.
 *
 * The three design points carry the requirements, and they are the same three
 * the Go reference states, restated here because a reader of this file should
 * not have to go and find that one.
 *
 * THE RECORD KEY IS DELIBERATELY INDEPENDENT OF THE DEVICE KEK. A recovery
 * record exists to open on a DIFFERENT device; anything the Android keystore
 * wrapped would be unopenable there by construction. So the key comes from the
 * passphrase and nothing else - which also means the record's security is
 * exactly the passphrase's, a limitation the threat model records as RR-5.
 *
 * THE PARAMETERS TRAVEL WITH THE RECORD, AND ARE AUTHENTICATED. A record must
 * still open after the register's defaults move, so it carries the factors it
 * was written with. That invites a downgrade: edit the file to say "one pass,
 * 8 KiB" and guessing gets cheap. Two things stop it - the parameters are AEAD
 * associated data, so an edited header fails authentication rather than yielding
 * a weaker key, and RecoveryParams.validate refuses anything outside its bounds
 * BEFORE any derivation is attempted.
 *
 * CONTENT ONLY. Nothing here knows what key material looks like. This class
 * seals whatever bytes it is handed; the callers that assemble those bytes are
 * the ones tested for never including private keys.
 */

package com.lumos.app.recovery;

import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/** Seals content under a passphrase, and opens what it sealed. */
public final class RecoveryRecord {

    /** The only record layout this build writes or reads. */
    public static final byte FORMAT_VERSION = 1;

    /** ChaCha20-Poly1305 (IETF) nonce length in bytes. */
    public static final int NONCE_BYTES = 12;

    /** Magic: eight bytes, so a file-type sniff is unambiguous. */
    private static final byte[] MAGIC = "LUMOSREC".getBytes(StandardCharsets.US_ASCII);

    private static final int VERSION_BYTES = 1;
    private static final int MEMORY_BYTES = 4;
    private static final int ITERATIONS_BYTES = 4;
    private static final int PARALLELISM_BYTES = 1;

    private static final int VERSION_OFFSET = 8;
    private static final int MEMORY_OFFSET = VERSION_OFFSET + VERSION_BYTES;
    private static final int ITERATIONS_OFFSET = MEMORY_OFFSET + MEMORY_BYTES;
    private static final int PARALLELISM_OFFSET = ITERATIONS_OFFSET + ITERATIONS_BYTES;
    private static final int SALT_OFFSET = PARALLELISM_OFFSET + PARALLELISM_BYTES;

    /** Everything the AEAD authenticates: magic, version, work factors, salt. */
    public static final int AUTHENTICATED_HEADER_BYTES =
            SALT_OFFSET + LumosConstants.RECOVERY_ARGON2ID_SALT_BYTES;

    /** Everything before the ciphertext: the authenticated header plus the nonce. */
    public static final int HEADER_BYTES = AUTHENTICATED_HEADER_BYTES + NONCE_BYTES;

    /** Process-wide CSPRNG; per-call construction is a defect SpotBugs flags. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RecoveryCrypto crypto;

    /**
     * @param crypto the primitives adapter; one per process
     */
    public RecoveryRecord(final RecoveryCrypto crypto) {
        this.crypto = crypto;
    }

    /**
     * Seals content under a passphrase, at the register's frozen work factors.
     *
     * <p>The salt and nonce are drawn fresh, so exporting the same content twice
     * with the same passphrase produces different bytes. That is required: a
     * repeated (key, nonce) pair under ChaCha20-Poly1305 is a total break, not a
     * weakness, and two identical exports would also be linkable to each other.
     *
     * @param passphrase the user's passphrase, UTF-8 encoded
     * @param content the bytes to seal; the caller decides what they are
     * @return the complete record
     * @throws RecoveryException if the seal fails
     */
    public byte[] export(final byte[] passphrase, final byte[] content) throws RecoveryException {
        final byte[] salt = new byte[LumosConstants.RECOVERY_ARGON2ID_SALT_BYTES];
        final byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        return exportWith(passphrase, content, salt, nonce, RecoveryParams.defaults());
    }

    /**
     * The deterministic core of {@link #export}, with the salt, nonce and work
     * factors supplied rather than generated.
     *
     * <p>Public for the conformance tests, which cannot pin a record whose salt
     * and nonce are fresh per call. Application code wants {@link #export}: a
     * caller that chooses its own nonce can repeat one.
     *
     * @param passphrase the user's passphrase, UTF-8 encoded
     * @param content the bytes to seal
     * @param salt exactly RECOVERY_ARGON2ID_SALT_BYTES of salt
     * @param nonce exactly NONCE_BYTES of nonce
     * @param params the work factors to write into the header
     * @return the complete record
     * @throws RecoveryException if the parameters are unacceptable or the seal fails
     */
    public byte[] exportWith(final byte[] passphrase, final byte[] content, final byte[] salt,
            final byte[] nonce, final RecoveryParams params) throws RecoveryException {
        if (salt.length != LumosConstants.RECOVERY_ARGON2ID_SALT_BYTES || nonce.length != NONCE_BYTES) {
            throw RecoveryException.openFailed();
        }
        final byte[] header = authenticatedHeader(params, salt);
        final byte[] key = crypto.argon2id(passphrase, salt, params);
        try {
            final byte[] sealed = crypto.seal(key, nonce, content, header);
            final byte[] record = new byte[HEADER_BYTES + sealed.length];
            System.arraycopy(header, 0, record, 0, header.length);
            System.arraycopy(nonce, 0, record, header.length, nonce.length);
            System.arraycopy(sealed, 0, record, HEADER_BYTES, sealed.length);
            return record;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Recovers the content, refusing anything it cannot fully authenticate.
     *
     * <p>Every opening failure reports the same code and the same message: a
     * wrong passphrase, a tampered header and a corrupted ciphertext are one
     * answer, because telling them apart is an oracle over the passphrase
     * (TRS-CRY-013). The one exception is a parameter set outside the accepted
     * bounds, which is reported separately - that is a property of the FILE,
     * learnable by reading it, so naming it discloses nothing.
     *
     * @param passphrase the user's passphrase, UTF-8 encoded
     * @param record the bytes read from the file
     * @return the sealed content
     * @throws RecoveryException on any failure
     */
    public byte[] open(final byte[] passphrase, final byte[] record) throws RecoveryException {
        requireWellFormedHeader(record);

        // Validated BEFORE anything is derived. A record claiming 16 GiB must be
        // refused, not attempted: allocating what a hostile header asked for is a
        // denial of service for anyone who can hand the user a file.
        final RecoveryParams params = paramsOf(record);
        params.validate();

        final byte[] salt = Arrays.copyOfRange(record, SALT_OFFSET, AUTHENTICATED_HEADER_BYTES);
        final byte[] nonce = Arrays.copyOfRange(record, AUTHENTICATED_HEADER_BYTES, HEADER_BYTES);
        final byte[] aad = Arrays.copyOfRange(record, 0, AUTHENTICATED_HEADER_BYTES);
        final byte[] ciphertext = Arrays.copyOfRange(record, HEADER_BYTES, record.length);

        final byte[] key = crypto.argon2id(passphrase, salt, params);
        try {
            return crypto.open(key, nonce, ciphertext, aad);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Reads a record's declared work factors without opening it, so a caller can
     * warn about an old weak export before asking for a passphrase.
     *
     * @param record the bytes read from the file
     * @return the declared work factors, not yet validated
     * @throws RecoveryException if the record is too short to carry a header
     */
    public static RecoveryParams paramsOf(final byte[] record) throws RecoveryException {
        if (record.length < HEADER_BYTES) {
            throw RecoveryException.openFailed();
        }
        return new RecoveryParams(
                unsigned32(record, MEMORY_OFFSET),
                unsigned32(record, ITERATIONS_OFFSET),
                record[PARALLELISM_OFFSET] & 0xFF);
    }

    /**
     * Renders a record's header for diagnostics. It never touches the
     * ciphertext, so it is safe to log.
     *
     * @param record the bytes read from the file
     * @return a one-line description
     */
    public static String describe(final byte[] record) {
        try {
            return "recovery record: " + paramsOf(record);
        } catch (RecoveryException e) {
            return "unreadable recovery record";
        }
    }

    /**
     * @param record the bytes read from the file
     * @throws RecoveryException if this is not a record of a layout we know
     */
    private static void requireWellFormedHeader(final byte[] record) throws RecoveryException {
        if (record.length < HEADER_BYTES) {
            throw RecoveryException.openFailed();
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (record[i] != MAGIC[i]) {
                throw RecoveryException.openFailed();
            }
        }
        if (record[VERSION_OFFSET] != FORMAT_VERSION) {
            throw RecoveryException.openFailed();
        }
    }

    /**
     * The AEAD associated data. Binding the work factors is what makes a
     * downgrade edit fail authentication instead of producing a cheaper key.
     *
     * @param params the work factors
     * @param salt the record's salt
     * @return the authenticated header bytes
     */
    private static byte[] authenticatedHeader(final RecoveryParams params, final byte[] salt) {
        final byte[] header = new byte[AUTHENTICATED_HEADER_BYTES];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[VERSION_OFFSET] = FORMAT_VERSION;
        putUnsigned32(header, MEMORY_OFFSET, params.memoryKib());
        putUnsigned32(header, ITERATIONS_OFFSET, params.iterations());
        header[PARALLELISM_OFFSET] = (byte) params.parallelism();
        System.arraycopy(salt, 0, header, SALT_OFFSET, salt.length);
        return header;
    }

    /**
     * @param bytes the buffer
     * @param offset where the field starts
     * @return the big-endian unsigned 32-bit field, widened so 0xFFFFFFFF does
     *     not arrive as -1
     */
    private static long unsigned32(final byte[] bytes, final int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    /**
     * @param bytes the buffer
     * @param offset where the field starts
     * @param value the value to write, big-endian
     */
    private static void putUnsigned32(final byte[] bytes, final int offset, final long value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}

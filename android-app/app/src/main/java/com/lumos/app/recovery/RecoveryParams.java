/*
 * Lumos - android-app/app / The Argon2id work factors a recovery record carries.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, TRS-CRY-001
 * ADRs: ADR-003
 * Threading: any
 * Notes: The header's three parameter fields, and the rules for accepting them.
 * Two things here are worth the reading time.
 *
 * THE FIELDS ARE HELD IN long, NOT int. They are unsigned 32-bit on the wire, and
 * a hostile record may claim 0xFFFFFFFF. Read into an int that is -1: below every
 * ceiling, above nothing, so a ceiling check written the obvious way lets it
 * through and whatever runs next gets a negative allocation size. The vector
 * family carries inflation_memory_uint32_max for exactly this mistake.
 *
 * THE CEILINGS ARE NOT THE REGISTER'S MAXIMA, and that is not an oversight.
 * The register's maxima bound what THIS build writes; a record written by a later
 * build with raised defaults must still open here or every upgrade strands its
 * own backups. These are absolute limits on what any record may ask this process
 * to do - far above any plausible parameter set, far below anything that
 * threatens the device. Parallelism is the exception and is documented at its
 * own constant.
 */

package com.lumos.app.recovery;

import com.lumos.protocol.LumosConstants;

/** The Argon2id work factors of one record, and whether they are acceptable. */
public final class RecoveryParams {

    /**
     * The absolute memory ceiling, 1 GiB: two orders of magnitude above the
     * register default and beyond any parameter set a real device would write.
     */
    public static final long MAX_ACCEPTABLE_MEMORY_KIB = 1024L * 1024L;

    /** The absolute time-cost ceiling. */
    public static final long MAX_ACCEPTABLE_ITERATIONS = 64L;

    /**
     * The only Argon2id lane count this format admits, and the reason is
     * portability rather than cost.
     *
     * <p>libsodium's {@code crypto_pwhash} fixes Argon2's lanes and threads at
     * one and exposes no multi-lane entry point at all - and libsodium is the
     * binding on both shipping platforms. A record declaring two lanes would
     * open in the Go reference and be unopenable on every device the app ships
     * to: a recovery record no user could recover from. All three
     * implementations refuse it, and the cross-language vectors pin the refusal.
     *
     * <p>Note this sits BELOW the register's parallelism_max of 4, unlike the two
     * ceilings above. That maximum consequently describes a lane count no
     * shipping platform can execute.
     */
    public static final int SUPPORTED_PARALLELISM = 1;

    private final long memoryKib;
    private final long iterations;
    private final int parallelism;

    /**
     * @param memoryKib the memory cost in KiB, as an unsigned 32-bit value
     * @param iterations the time cost, as an unsigned 32-bit value
     * @param parallelism the lane count, as an unsigned 8-bit value
     */
    public RecoveryParams(final long memoryKib, final long iterations, final int parallelism) {
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    /**
     * @return the register's frozen work factors, which is what an export writes
     */
    public static RecoveryParams defaults() {
        return new RecoveryParams(
                LumosConstants.RECOVERY_ARGON2ID_MEMORY_KIB,
                LumosConstants.RECOVERY_ARGON2ID_ITERATIONS,
                LumosConstants.RECOVERY_ARGON2ID_PARALLELISM);
    }

    /** @return the memory cost in KiB */
    public long memoryKib() {
        return memoryKib;
    }

    /** @return the time cost */
    public long iterations() {
        return iterations;
    }

    /** @return the lane count */
    public int parallelism() {
        return parallelism;
    }

    /**
     * Refuses work factors outside the ceilings, outside the single-lane rule, or
     * below the register's minima - in that order, so nothing absurd is ever
     * measured against a minimum first.
     *
     * @throws RecoveryException with ERR_CRYPTO_RECOVERY_PARAMS_REJECTED
     */
    public void validate() throws RecoveryException {
        if (memoryKib > MAX_ACCEPTABLE_MEMORY_KIB) {
            throw RecoveryException.paramsRejected("memory cost beyond the acceptable ceiling");
        }
        if (iterations > MAX_ACCEPTABLE_ITERATIONS) {
            throw RecoveryException.paramsRejected("time cost beyond the acceptable ceiling");
        }
        if (parallelism != SUPPORTED_PARALLELISM) {
            throw RecoveryException.paramsRejected(
                    "only a single Argon2id lane is executable on every platform");
        }
        if (memoryKib < LumosConstants.RECOVERY_ARGON2ID_MEMORY_KIB_MIN) {
            throw RecoveryException.paramsRejected("memory cost below the register minimum");
        }
        if (iterations < LumosConstants.RECOVERY_ARGON2ID_ITERATIONS_MIN) {
            throw RecoveryException.paramsRejected("time cost below the register minimum");
        }
        // No separate parallelism minimum: the single-lane rule above is stricter
        // than the register's parallelism_min for as long as that minimum is 1,
        // and RecoveryRecordInstrumentedTest fires if the register ever moves it.
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof RecoveryParams)) {
            return false;
        }
        final RecoveryParams that = (RecoveryParams) other;
        return memoryKib == that.memoryKib
                && iterations == that.iterations
                && parallelism == that.parallelism;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(memoryKib) * 31 * 31 + Long.hashCode(iterations) * 31 + parallelism;
    }

    @Override
    public String toString() {
        return "argon2id m=" + memoryKib + " KiB t=" + iterations + " p=" + parallelism;
    }
}

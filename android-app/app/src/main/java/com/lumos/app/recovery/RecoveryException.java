/*
 * Lumos - android-app/app / The coded failure of a recovery import or export.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, TRS-CRY-013
 * ADRs: ADR-003
 * Threading: any
 * Notes: Carries one of the two stable codes from the generated register and
 * nothing else. There is no cause chain and no cipher detail on purpose: a
 * caller that can tell a wrong passphrase from a tampered file has an oracle
 * over the passphrase, and a stack trace naming the failing cipher operation is
 * exactly such an oracle written down for it (TRS-CRY-013).
 *
 * The asymmetry is deliberate and matches the Go reference. Opening failures are
 * one code with one message. A parameter rejection is a separate code, because
 * that is a property of the FILE readable without any passphrase attempt, so
 * naming it discloses nothing and telling the user their old export is too weak
 * to trust is worth more than uniformity.
 */

package com.lumos.app.recovery;

import com.lumos.protocol.LumosErrors;

/** A recovery record could not be produced, or could not be opened. */
public final class RecoveryException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The single message every opening failure reports. */
    static final String OPEN_FAILED_DETAIL = "record could not be opened";

    private final String code;

    /**
     * @param code one of the ERR_CRYPTO_RECOVERY_* codes from the register
     * @param detail the message; the same string for every opening failure
     */
    RecoveryException(final String code, final String detail) {
        super(detail);
        this.code = code;
    }

    /**
     * @return a failure that says nothing about which cause it was
     */
    static RecoveryException openFailed() {
        return new RecoveryException(LumosErrors.ERR_CRYPTO_RECOVERY_OPEN_FAILED, OPEN_FAILED_DETAIL);
    }

    /**
     * @param detail which bound the declared work factors fell outside
     * @return a parameter rejection, which may name what was wrong
     */
    static RecoveryException paramsRejected(final String detail) {
        return new RecoveryException(LumosErrors.ERR_CRYPTO_RECOVERY_PARAMS_REJECTED, detail);
    }

    /**
     * @return the stable error code this failure carries
     */
    public String code() {
        return code;
    }
}

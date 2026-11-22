/*
 * Lumos - android-app/app / Why a relay call did not succeed, sorted by what the caller does next.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-003, SRS-RMT-009, TRS-BCK-007
 * ADRs: ADR-001
 * Threading: any
 * Notes: Callers never look at statuses; they look at the kind. OFFLINE and
 * BUSY keep everything queued for the next attempt (SRS-RMT-003), and BUSY
 * may carry the server's Retry-After. AUTH means the credential is gone and
 * the device registers again. REFUSED is permanent for that request. The
 * stable code is for diagnostics only and is never shown to the user
 * (SRS-RMT-009, TRS-UX-005).
 */

package com.lumos.app.relay;

import com.lumos.protocol.LumosErrors;

/** A relay call that did not succeed. */
public final class RelayException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What the caller does next. */
    public enum Kind {
        /** No response: no network, refused, timed out. Retry when a path returns. */
        OFFLINE,
        /** The server or the recipient's mailbox cannot take it now. Retry later. */
        BUSY,
        /** The credential was refused. Register again, then retry. */
        AUTH,
        /** The thing asked for does not exist (an unknown or bundle-less peer). */
        NOT_FOUND,
        /** This request will never succeed as sent. */
        REFUSED
    }

    private final Kind kind;
    private final String code;
    private final long retryAfterSeconds;

    RelayException(final Kind kind, final String code, final long retryAfterSeconds) {
        super(kind + (code.isEmpty() ? "" : " " + code));
        this.kind = kind;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static RelayException offline() {
        return new RelayException(Kind.OFFLINE, "", -1);
    }

    static RelayException malformedResponse() {
        return new RelayException(Kind.BUSY, LumosErrors.ERR_PAYLOAD_MALFORMED, -1);
    }

    /** @return what the caller does next */
    public Kind kind() {
        return kind;
    }

    /** @return the stable code, or empty; diagnostics only */
    public String code() {
        return code;
    }

    /** @return the server's Retry-After in seconds, or -1 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** @return true when the request should be retried later as it is */
    public boolean retryable() {
        return kind == Kind.OFFLINE || kind == Kind.BUSY;
    }
}

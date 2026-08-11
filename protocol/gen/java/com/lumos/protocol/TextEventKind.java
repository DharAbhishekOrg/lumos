/*
 * Lumos - protocol/gen/java / Generated TextEventKind enum.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * GENERATED - DO NOT EDIT. Generator: tools/constantsgen
 * Source register: protocol/constants/lumos_constants.json
 * Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
 * constants_hash: sha256:12510455720dcc2ff72291f6a428f31930434d3dcb95a8b871ed305edb159855
 */
package com.lumos.protocol;

/** AMD-005 inner event envelope kinds for class 0x10. */
public enum TextEventKind {
    /** Original text event */
    TEXT(0x00),
    /** Authenticated edit over the original message */
    EDIT(0x01),
    /** Authenticated delete over the original message */
    DELETE_FOR_EVERYONE(0x02),
    /** Authenticated reaction over the original message */
    REACTION(0x03);

    private final int code;

    TextEventKind(final int code) {
        this.code = code;
    }

    /** @return the canonical wire code. */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire code.
     *
     * @param code the wire code
     * @return the matching constant, or null when the code is not defined by this
     *         protocol version; callers apply TRS-PROTO-006 fail-closed handling.
     */
    public static TextEventKind fromCode(final int code) {
        for (final TextEventKind v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }
}

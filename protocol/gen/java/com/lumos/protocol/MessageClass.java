/*
 * Lumos - protocol/gen/java / Generated MessageClass enum.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * GENERATED - DO NOT EDIT. Generator: tools/constantsgen
 * Source register: protocol/constants/lumos_constants.json
 * Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
 * constants_hash: sha256:83f32e68c912a8df9a6392194ac6691a45c6e8ae62fc6d60640424df45cbdc91
 */
package com.lumos.protocol;

/** TRS Sec. 9.2 canonical message classes. */
public enum MessageClass {
    /** Version/capability/session bootstrap */
    HELLO(0x01),
    /** Capability and session acknowledgement */
    HELLO_ACK(0x02),
    /** Consent-stage request payload */
    INTEREST_REQUEST(0x03),
    /** Accept/decline/ignore-safe response */
    INTEREST_RESPONSE(0x04),
    /** Approved preview fields */
    PROFILE_PREVIEW(0x05),
    /** Approved full profile after match */
    PROFILE_FULL(0x06),
    /** Ratcheted private text event carrying the inner event envelope (AMD-005) */
    TEXT_MESSAGE(0x10),
    /** Delivered state */
    DELIVERY_RECEIPT(0x11),
    /** Optional read state */
    READ_RECEIPT(0x12),
    /** Optional ephemeral state */
    TYPING(0x13),
    /** Media/file metadata and digest */
    OBJECT_META(0x20),
    /** Encrypted object fragment */
    OBJECT_CHUNK(0x21),
    /** Selective acknowledgement bitmap/ranges */
    OBJECT_ACK(0x22),
    /** Cancellation/cleanup */
    OBJECT_CANCEL(0x23),
    /** Voice-note codec/duration metadata */
    VOICE_META(0x24),
    /** Opaque relay wrapper */
    MESH_RELAY(0x30),
    /** Short-TTL opaque mailbox frame */
    STORE_FORWARD(0x31),
    /** Signed venue package reference */
    VENUE_PACKAGE_HINT(0x40),
    /** Moderated graphical room event */
    VENUE_ROOM_EVENT(0x41),
    /** Signed voucher/bootstrap token */
    VOUCHER_TOKEN(0x42),
    /** Liveness and route health */
    HEARTBEAT(0x50),
    /** Stable machine-readable failure */
    ERROR(0x7F);

    private final int code;

    MessageClass(final int code) {
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
    public static MessageClass fromCode(final int code) {
        for (final MessageClass v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        return null;
    }
}

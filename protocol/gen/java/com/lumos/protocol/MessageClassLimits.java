/*
 * Lumos - protocol/gen/java / Generated per-class maximum payload sizes.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * GENERATED - DO NOT EDIT. Generator: tools/constantsgen
 * Source register: protocol/constants/lumos_constants.json
 * Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
 * constants_hash: sha256:4fb89c6c38259e018245239b69a55a6eb61c015ce5f49709774bd2618f384cb4
 */
package com.lumos.protocol;

import java.util.Map;

/** Per-class payload bounds resolved from the constants register (TRS-PROTO-007). */
public final class MessageClassLimits {

    private MessageClassLimits() {
        throw new AssertionError("no instances");
    }

    /** Maximum ciphertext payload in bytes, by message class. */
    public static final Map<MessageClass, Long> MAX_PAYLOAD_BYTES = Map.ofEntries(
        Map.entry(MessageClass.HELLO, 1228L),
        Map.entry(MessageClass.HELLO_ACK, 1228L),
        Map.entry(MessageClass.INTEREST_REQUEST, 1228L),
        Map.entry(MessageClass.INTEREST_RESPONSE, 1228L),
        Map.entry(MessageClass.PROFILE_PREVIEW, 65536L),
        Map.entry(MessageClass.PROFILE_FULL, 2097152L),
        Map.entry(MessageClass.TEXT_MESSAGE, 32768L),
        Map.entry(MessageClass.DELIVERY_RECEIPT, 1228L),
        Map.entry(MessageClass.READ_RECEIPT, 1228L),
        Map.entry(MessageClass.TYPING, 1228L),
        Map.entry(MessageClass.OBJECT_META, 1228L),
        Map.entry(MessageClass.OBJECT_CHUNK, 65536L),
        Map.entry(MessageClass.OBJECT_ACK, 1228L),
        Map.entry(MessageClass.OBJECT_CANCEL, 1228L),
        Map.entry(MessageClass.VOICE_META, 1228L),
        Map.entry(MessageClass.MESH_RELAY, 32768L),
        Map.entry(MessageClass.STORE_FORWARD, 32768L),
        Map.entry(MessageClass.VENUE_PACKAGE_HINT, 1228L),
        Map.entry(MessageClass.VENUE_ROOM_EVENT, 1228L),
        Map.entry(MessageClass.VOUCHER_TOKEN, 1228L),
        Map.entry(MessageClass.HEARTBEAT, 1228L),
        Map.entry(MessageClass.ERROR, 1228L)
    );

    /**
     * Returns the payload bound for a message class.
     *
     * @param mc a message class
     * @return the bound in bytes, or null when this protocol version does not
     *         define the class; callers apply TRS-PROTO-006 handling
     */
    public static Long maxPayloadBytes(final MessageClass mc) {
        return MAX_PAYLOAD_BYTES.get(mc);
    }
}

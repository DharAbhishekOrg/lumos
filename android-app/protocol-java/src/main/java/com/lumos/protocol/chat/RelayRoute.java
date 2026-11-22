/*
 * Lumos - android-app/protocol-java / RELAY_ROUTE: this device's relay address for one matched peer.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-016, SRS-MAT-005, TRS-PROTO-007
 * ADRs: ADR-002
 * Threading: any
 * Notes: Mirrors protocol/chat/route.go and is held to chat/receipts.json.
 * CR-005 (owner-accepted 2026-11-23): sent after a match over the
 * authenticated nearby session, never over the relay. The layout is
 * version u8 = 1 | routing_id[16] | capability[32], 49 bytes exactly, and an
 * all-zero field is refused because no CSPRNG ever mints one.
 */

package com.lumos.protocol.chat;

import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.Frame;
import java.util.Arrays;

/**
 * One RELAY_ROUTE payload.
 *
 * @param routingId the sender's routing id
 * @param capability the send capability the sender minted for this peer
 */
public record RelayRoute(byte[] routingId, byte[] capability) {

    /** A routing id's width. */
    public static final int ROUTING_ID_SIZE = 16;
    /** A capability's width. */
    public static final int CAPABILITY_SIZE = 32;
    /** The encoded size. */
    public static final int SIZE = 1 + ROUTING_ID_SIZE + CAPABILITY_SIZE;
    private static final int VERSION = 1;

    /**
     * Copies.
     *
     * @param routingId see the record
     * @param capability see the record
     */
    public RelayRoute {
        if (routingId.length != ROUTING_ID_SIZE || capability.length != CAPABILITY_SIZE) {
            throw new IllegalArgumentException("a relay route is a 16-byte routing id and a 32-byte capability");
        }
        routingId = routingId.clone();
        capability = capability.clone();
    }

    /** @return a copy of the routing id */
    @Override
    public byte[] routingId() {
        return routingId.clone();
    }

    /** @return a copy of the capability */
    @Override
    public byte[] capability() {
        return capability.clone();
    }

    /** @return version | routing_id | capability */
    public byte[] encode() {
        final byte[] out = new byte[SIZE];
        out[0] = VERSION;
        System.arraycopy(routingId, 0, out, 1, ROUTING_ID_SIZE);
        System.arraycopy(capability, 0, out, 1 + ROUTING_ID_SIZE, CAPABILITY_SIZE);
        return out;
    }

    /**
     * Parses a RELAY_ROUTE payload.
     *
     * @param b the payload
     * @return the route
     * @throws Frame.WireException ERR_PAYLOAD_MALFORMED on any other length or version, or an all-zero field
     */
    public static RelayRoute decode(final byte[] b) throws Frame.WireException {
        if (b.length != SIZE) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "relay route is not 49 bytes");
        }
        if (b[0] != VERSION) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "unknown relay route version");
        }
        final byte[] rid = Arrays.copyOfRange(b, 1, 1 + ROUTING_ID_SIZE);
        final byte[] cap = Arrays.copyOfRange(b, 1 + ROUTING_ID_SIZE, SIZE);
        if (Arrays.equals(rid, new byte[ROUTING_ID_SIZE]) || Arrays.equals(cap, new byte[CAPABILITY_SIZE])) {
            throw new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, "relay route field is all zero");
        }
        return new RelayRoute(rid, cap);
    }
}

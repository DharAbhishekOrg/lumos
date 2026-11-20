/*
 * Lumos - android-app/protocol-java / What may flow between two people, by state.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-005, SRS-MAT-006, TRS-PAT-002
 * ADRs: ADR-002
 * Threading: any
 * Notes: SRS-MAT-006 as a table every send and every receive consults. The
 * conversation classes - full profile, text and its receipts, typing, every
 * object and voice class - pass only in MATCHED; the consent-stage classes
 * (request, response, preview) pass in every state except BLOCKED; and the
 * session, mesh, venue and housekeeping classes are not this gate's to
 * decide (their own work packages own them) and pass through. The three
 * sets are disjoint and together cover every MessageClass value, which the
 * test asserts by walking values() - a class added to the register lands in
 * no set and fails there, so a new class cannot open a pre-match path by
 * default.
 */

package com.lumos.protocol.match;

import com.lumos.protocol.MessageClass;
import java.util.EnumSet;
import java.util.Set;

/** The SRS-MAT-006 pre-match gate. */
public final class ConsentGate {

    /** Classes that exist only between matched people (SRS-MAT-005/006). */
    public static final Set<MessageClass> MATCH_ONLY = EnumSet.of(
            MessageClass.PROFILE_FULL, MessageClass.TEXT_MESSAGE,
            MessageClass.DELIVERY_RECEIPT, MessageClass.READ_RECEIPT, MessageClass.TYPING,
            MessageClass.OBJECT_META, MessageClass.OBJECT_CHUNK, MessageClass.OBJECT_ACK,
            MessageClass.OBJECT_CANCEL, MessageClass.VOICE_META);

    /** The consent stage itself: open in every state but BLOCKED. */
    public static final Set<MessageClass> CONSENT_STAGE = EnumSet.of(
            MessageClass.INTEREST_REQUEST, MessageClass.INTEREST_RESPONSE,
            MessageClass.PROFILE_PREVIEW);

    /** Session, mesh, venue and housekeeping: other gates own these. */
    public static final Set<MessageClass> NOT_THIS_GATE = EnumSet.of(
            MessageClass.HELLO, MessageClass.HELLO_ACK, MessageClass.MESH_RELAY,
            MessageClass.STORE_FORWARD, MessageClass.VENUE_PACKAGE_HINT,
            MessageClass.VENUE_ROOM_EVENT, MessageClass.VOUCHER_TOKEN,
            MessageClass.HEARTBEAT, MessageClass.ERROR);

    private ConsentGate() {
        throw new AssertionError("no instances");
    }

    /**
     * Whether a class may flow with a peer in a state. Unknown classes fail
     * closed.
     *
     * @param messageClass the frame's class
     * @param state where the relationship with that peer stands
     * @return true when the frame may be sent or delivered
     */
    public static boolean permits(final MessageClass messageClass,
            final MatchMachine.State state) {
        if (MATCH_ONLY.contains(messageClass)) {
            return state == MatchMachine.State.MATCHED;
        }
        if (CONSENT_STAGE.contains(messageClass)) {
            return state != MatchMachine.State.BLOCKED;
        }
        return NOT_THIS_GATE.contains(messageClass);
    }
}

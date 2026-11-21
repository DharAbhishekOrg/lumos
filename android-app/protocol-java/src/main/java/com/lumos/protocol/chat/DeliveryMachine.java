/*
 * Lumos - android-app/protocol-java / The outgoing-message delivery machine, mirrored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-014, SRS-TRN-008, TRS-PAT-002
 * ADRs: ADR-002
 * Threading: any
 * Notes: The Go reference row for row, held to it by the committed
 * chat/delivery_states.json vectors. The EDP guardrail is structural: only
 * the recipient's receipts reach DELIVERED or READ, receipts never move a
 * message backward, and a recipient receipt overrides a local FAILED. The
 * tick mapping is the owner's WhatsApp-style ruling of 2026-11-21, carried
 * here so the Android binder and the iOS view render the same glyph for the
 * same state.
 */

package com.lumos.protocol.chat;

import java.util.Map;

/** The SRS-RMT-005 delivery machine and its ticks. */
public final class DeliveryMachine {

    /** Where one outgoing message stands. */
    public enum State {
        /** In the outbox, no attempt running. */
        QUEUED,
        /** A transport attempt is in flight. */
        SENDING,
        /** A transport accepted it: one tick. */
        SENT,
        /** The recipient's device acknowledged it: two ticks. */
        DELIVERED,
        /** The recipient read it: two blue ticks. */
        READ,
        /** The retry budget ran out: red mark, tap to retry. */
        FAILED,
        /** Nearby-only content waiting for a nearby path. */
        LOCAL_ONLY_WAITING
    }

    /** One typed input. */
    public enum Event {
        SEND_STARTED, TRANSPORT_ACCEPTED, TRANSPORT_RETRYABLE, ATTEMPTS_EXHAUSTED,
        DELIVERY_RECEIPT, READ_RECEIPT, USER_RETRY, LOCAL_PATH_UNAVAILABLE,
        LOCAL_PATH_AVAILABLE
    }

    /** The glyph a state renders as (owner ruling 2026-11-21). */
    public enum Tick {
        /** Queued or sending. */
        CLOCK("clock"),
        /** Sent: one grey tick. */
        SINGLE("single"),
        /** Delivered: two grey ticks. */
        DOUBLE("double"),
        /** Read: two blue ticks. */
        DOUBLE_READ("double_read"),
        /** Failed: red mark. */
        FAILED("failed"),
        /** Waiting for a nearby path. */
        NEARBY("nearby");

        private final String vectorName;

        Tick(final String vectorName) {
            this.vectorName = vectorName;
        }

        /** @return the stable name the vectors use */
        public String vectorName() {
            return vectorName;
        }
    }

    /**
     * What to send back for an incoming message (SRS-RMT-014).
     *
     * @param delivery whether a delivery receipt goes back (always)
     * @param read whether a read receipt goes back
     */
    public record Receipts(boolean delivery, boolean read) { }

    private static final Map<State, Map<Event, State>> TABLE = Map.of(
            State.QUEUED, Map.of(
                    Event.SEND_STARTED, State.SENDING,
                    Event.LOCAL_PATH_UNAVAILABLE, State.LOCAL_ONLY_WAITING),
            State.SENDING, Map.of(
                    Event.TRANSPORT_ACCEPTED, State.SENT,
                    Event.TRANSPORT_RETRYABLE, State.QUEUED,
                    Event.ATTEMPTS_EXHAUSTED, State.FAILED),
            State.FAILED, Map.of(Event.USER_RETRY, State.QUEUED),
            State.LOCAL_ONLY_WAITING, Map.of(Event.LOCAL_PATH_AVAILABLE, State.QUEUED));

    private DeliveryMachine() {
        throw new AssertionError("no instances");
    }

    /**
     * Applies one event; unlisted pairs absorb.
     *
     * @param state where the message stands
     * @param event what happened
     * @return the next state
     */
    public static State step(final State state, final Event event) {
        if (event == Event.DELIVERY_RECEIPT || event == Event.READ_RECEIPT) {
            final State target = event == Event.DELIVERY_RECEIPT ? State.DELIVERED : State.READ;
            return rank(target) > rank(state) ? target : state;
        }
        final Map<Event, State> row = TABLE.get(state);
        if (row == null) {
            return state;
        }
        return row.getOrDefault(event, state);
    }

    /**
     * The glyph a state renders as.
     *
     * @param state the message's state
     * @return its tick
     */
    public static Tick tickOf(final State state) {
        switch (state) {
            case SENT:
                return Tick.SINGLE;
            case DELIVERED:
                return Tick.DOUBLE;
            case READ:
                return Tick.DOUBLE_READ;
            case FAILED:
                return Tick.FAILED;
            case LOCAL_ONLY_WAITING:
                return Tick.NEARBY;
            default:
                return Tick.CLOCK;
        }
    }

    /**
     * SRS-RMT-014: a delivery receipt always goes back; a read receipt only
     * when the user allows them and has opened the message.
     *
     * @param readReceiptsEnabled the user's read-receipt setting
     * @param opened whether the user opened the message
     * @return the receipts to send
     */
    public static Receipts receiptsToSend(final boolean readReceiptsEnabled, final boolean opened) {
        return new Receipts(true, readReceiptsEnabled && opened);
    }

    /** @return the vector vocabulary for states */
    public static Map<State, String> stateNames() {
        return Map.of(State.QUEUED, "queued", State.SENDING, "sending", State.SENT, "sent",
                State.DELIVERED, "delivered", State.READ, "read", State.FAILED, "failed",
                State.LOCAL_ONLY_WAITING, "local_only_waiting");
    }

    /** @return the vector vocabulary for events */
    public static Map<String, Event> eventsByName() {
        return Map.of("send_started", Event.SEND_STARTED,
                "transport_accepted", Event.TRANSPORT_ACCEPTED,
                "transport_retryable", Event.TRANSPORT_RETRYABLE,
                "attempts_exhausted", Event.ATTEMPTS_EXHAUSTED,
                "delivery_receipt", Event.DELIVERY_RECEIPT,
                "read_receipt", Event.READ_RECEIPT,
                "user_retry", Event.USER_RETRY,
                "local_path_unavailable", Event.LOCAL_PATH_UNAVAILABLE,
                "local_path_available", Event.LOCAL_PATH_AVAILABLE);
    }

    private static int rank(final State state) {
        switch (state) {
            case SENT:
                return 1;
            case DELIVERED:
                return 2;
            case READ:
                return 3;
            default:
                return 0;
        }
    }
}

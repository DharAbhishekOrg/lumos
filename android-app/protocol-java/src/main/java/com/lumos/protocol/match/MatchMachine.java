/*
 * Lumos - android-app/protocol-java / The interest machine, mirrored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-003, SRS-MAT-004, SRS-MAT-005, SRS-MAT-014, TRS-PAT-002
 * ADRs: ADR-002
 * Threading: any
 * Notes: The Go reference's lookup table, row for row, held to it by the
 * committed timeline vectors rather than by anyone's diligence. The same
 * three properties carry the requirements: silence is structural (no
 * decline or ignore row carries a transmitting effect), idempotency is
 * absorption (an unlisted pair returns the same state with no effect), and
 * the two establishing effects are the only door to reveal and chat. The
 * vocabulary() maps exist for the conformance replay: the vectors speak the
 * stable string names, and the mapping lives beside the enums it names.
 */

package com.lumos.protocol.match;

import java.util.Map;

/** The per-peer interest state machine (TRS Sec. 4.2). */
public final class MatchMachine {

    /** Where one peer relationship stands, seen from this device. */
    public enum State {
        /** No interest either way. */
        NONE,
        /** We asked, nothing heard - also exactly what a declined asker sees. */
        REQUESTED_OUT,
        /** They asked; we have not answered. */
        REQUESTED_IN,
        /** Both asked before either answered - the race. */
        BOTH_REQUESTED,
        /** Mutual acceptance; the only state with an open door. */
        MATCHED,
        /** We declined them; the silence holds, a new request may retry. */
        DECLINED_IN,
        /** We ignored them; further requests absorb without surfacing. */
        IGNORED_IN,
        /** The match ended; dead echoes absorb, a new round may start. */
        UNMATCHED,
        /** Blocking wins from anywhere; nothing leaves it here. */
        BLOCKED
    }

    /** One typed input, local action or remote frame. */
    public enum Event {
        LOCAL_REQUEST, LOCAL_ACCEPT, LOCAL_DECLINE, LOCAL_IGNORE, LOCAL_BLOCK,
        LOCAL_UNMATCH, REMOTE_REQUEST, REMOTE_ACCEPT, REMOTE_UNMATCH
    }

    /** The single instruction a step hands back. */
    public enum Effect {
        NONE, SEND_REQUEST, ACCEPT_AND_ESTABLISH, MATCH_ESTABLISHED, SEND_UNMATCH;

        /** @return whether this effect opens the SRS-MAT-005 door */
        public boolean establishes() {
            return this == ACCEPT_AND_ESTABLISH || this == MATCH_ESTABLISHED;
        }
    }

    /**
     * One step's outcome.
     *
     * @param state the next state
     * @param effect the one instruction for the caller
     */
    public record Result(State state, Effect effect) { }

    private MatchMachine() {
        throw new AssertionError("no instances");
    }

    /** Every pair that CHANGES anything; unlisted pairs absorb. */
    private static final Map<State, Map<Event, Result>> TABLE = Map.of(
            State.NONE, Map.of(
                    Event.LOCAL_REQUEST, new Result(State.REQUESTED_OUT, Effect.SEND_REQUEST),
                    Event.REMOTE_REQUEST, new Result(State.REQUESTED_IN, Effect.NONE),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.REQUESTED_OUT, Map.of(
                    Event.REMOTE_REQUEST, new Result(State.BOTH_REQUESTED, Effect.NONE),
                    Event.REMOTE_ACCEPT, new Result(State.MATCHED, Effect.MATCH_ESTABLISHED),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.REQUESTED_IN, Map.of(
                    Event.LOCAL_ACCEPT, new Result(State.MATCHED, Effect.ACCEPT_AND_ESTABLISH),
                    Event.LOCAL_DECLINE, new Result(State.DECLINED_IN, Effect.NONE),
                    Event.LOCAL_IGNORE, new Result(State.IGNORED_IN, Effect.NONE),
                    Event.LOCAL_REQUEST, new Result(State.BOTH_REQUESTED, Effect.SEND_REQUEST),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.BOTH_REQUESTED, Map.of(
                    Event.LOCAL_ACCEPT, new Result(State.MATCHED, Effect.ACCEPT_AND_ESTABLISH),
                    Event.REMOTE_ACCEPT, new Result(State.MATCHED, Effect.MATCH_ESTABLISHED),
                    // Declining THEIR request leaves OUR request standing.
                    Event.LOCAL_DECLINE, new Result(State.REQUESTED_OUT, Effect.NONE),
                    Event.LOCAL_IGNORE, new Result(State.REQUESTED_OUT, Effect.NONE),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.MATCHED, Map.of(
                    Event.LOCAL_UNMATCH, new Result(State.UNMATCHED, Effect.SEND_UNMATCH),
                    Event.REMOTE_UNMATCH, new Result(State.UNMATCHED, Effect.NONE),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.DECLINED_IN, Map.of(
                    Event.REMOTE_REQUEST, new Result(State.REQUESTED_IN, Effect.NONE),
                    Event.LOCAL_REQUEST, new Result(State.REQUESTED_OUT, Effect.SEND_REQUEST),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.IGNORED_IN, Map.of(
                    Event.LOCAL_REQUEST, new Result(State.REQUESTED_OUT, Effect.SEND_REQUEST),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.UNMATCHED, Map.of(
                    Event.LOCAL_REQUEST, new Result(State.REQUESTED_OUT, Effect.SEND_REQUEST),
                    Event.REMOTE_REQUEST, new Result(State.REQUESTED_IN, Effect.NONE),
                    Event.LOCAL_BLOCK, new Result(State.BLOCKED, Effect.NONE)),
            State.BLOCKED, Map.of());

    /**
     * Applies one event. Unlisted pairs absorb: same state, no effect.
     *
     * @param state where the relationship stands
     * @param event what happened
     * @return the next state and the one instruction
     */
    public static Result step(final State state, final Event event) {
        final Result result = TABLE.getOrDefault(state, Map.of()).get(event);
        return result != null ? result : new Result(state, Effect.NONE);
    }

    /** @return the vector vocabulary for states */
    public static Map<State, String> stateNames() {
        return Map.of(State.NONE, "none", State.REQUESTED_OUT, "requested_out",
                State.REQUESTED_IN, "requested_in", State.BOTH_REQUESTED, "both_requested",
                State.MATCHED, "matched", State.DECLINED_IN, "declined_in",
                State.IGNORED_IN, "ignored_in", State.UNMATCHED, "unmatched",
                State.BLOCKED, "blocked");
    }

    /** @return the vector vocabulary for events */
    public static Map<String, Event> eventsByName() {
        return Map.of("local_request", Event.LOCAL_REQUEST, "local_accept", Event.LOCAL_ACCEPT,
                "local_decline", Event.LOCAL_DECLINE, "local_ignore", Event.LOCAL_IGNORE,
                "local_block", Event.LOCAL_BLOCK, "local_unmatch", Event.LOCAL_UNMATCH,
                "remote_request", Event.REMOTE_REQUEST, "remote_accept", Event.REMOTE_ACCEPT,
                "remote_unmatch", Event.REMOTE_UNMATCH);
    }

    /** @return the vector vocabulary for effects */
    public static Map<Effect, String> effectNames() {
        return Map.of(Effect.NONE, "none", Effect.SEND_REQUEST, "send_request",
                Effect.ACCEPT_AND_ESTABLISH, "accept_and_establish",
                Effect.MATCH_ESTABLISHED, "match_established",
                Effect.SEND_UNMATCH, "send_unmatch");
    }
}

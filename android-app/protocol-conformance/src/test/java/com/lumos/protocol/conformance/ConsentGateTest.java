/*
 * Lumos - android-app/protocol-conformance / The pre-match gate, walked exhaustively.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-005, SRS-MAT-006, TRS-PAT-002
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Two walks. Every MessageClass lands in exactly one of the three
 * sets - so a class added to the register forces a decision here. And every
 * class x every state is checked against the rule, so no conversation class
 * passes before MATCHED and nothing consent-stage passes once BLOCKED.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumos.protocol.MessageClass;
import com.lumos.protocol.match.ConsentGate;
import com.lumos.protocol.match.MatchMachine;
import org.junit.jupiter.api.Test;

/** ConsentGate over the full class x state product. */
public class ConsentGateTest {

    @Test
    void everyClassIsDecidedExactlyOnce() {
        for (final MessageClass mc : MessageClass.values()) {
            int homes = 0;
            homes += ConsentGate.MATCH_ONLY.contains(mc) ? 1 : 0;
            homes += ConsentGate.CONSENT_STAGE.contains(mc) ? 1 : 0;
            homes += ConsentGate.NOT_THIS_GATE.contains(mc) ? 1 : 0;
            assertEquals(1, homes, mc + " must belong to exactly one gate set "
                    + "(a new class cannot open a pre-match path by default)");
        }
    }

    @Test
    void conversationNeverFlowsBeforeAMatchAndConsentNeverAfterABlock() {
        for (final MessageClass mc : MessageClass.values()) {
            for (final MatchMachine.State state : MatchMachine.State.values()) {
                final boolean expected;
                if (ConsentGate.MATCH_ONLY.contains(mc)) {
                    expected = state == MatchMachine.State.MATCHED;
                } else if (ConsentGate.CONSENT_STAGE.contains(mc)) {
                    expected = state != MatchMachine.State.BLOCKED;
                } else {
                    expected = true;
                }
                assertEquals(expected, ConsentGate.permits(mc, state),
                        mc + " in " + state + " (SRS-MAT-006)");
            }
        }
    }
}

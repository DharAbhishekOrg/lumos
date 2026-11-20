/*
 * Lumos - android-app/protocol-conformance / The match family, replayed on Java.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-004, SRS-MAT-005, SRS-MAT-007, SRS-MAT-014, TRS-PAT-002,
 *             TRS-TST-001
 * ADRs: ADR-002, ADR-008
 * Threading: test-only
 * Notes: The committed derivations reproduce byte for byte and every
 * committed timeline replays step for step - which is what makes the Java
 * mirror a mirror rather than a sibling with opinions. The family-level
 * silence and single-door scans run here too, over the vectors as data, so
 * even a regenerated family cannot smuggle a transmitting decline past this
 * platform.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.match.MatchId;
import com.lumos.protocol.match.MatchMachine;
import com.lumos.protocol.match.MatchToken;
import org.junit.jupiter.api.Test;

/** Holds the Java match mirror to the committed golden vectors. */
public class MatchConformanceTest {

    private static final String FILE = "test-vectors/matches/match_timelines.json";

    @Test
    void committedDerivationsReproduceByteForByte() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "derivations").isEmpty(),
                "an empty derivation set proves nothing");
        for (final JsonElement e : Vectors.arr(root, "derivations")) {
            final JsonObject c = Vectors.obj(e);
            final String name = Vectors.str(c, "name");
            final byte[] a = Vectors.hex(c, "peer_a_hex");
            final byte[] b = Vectors.hex(c, "peer_b_hex");
            assertEquals(Vectors.str(c, "match_id_hex"),
                    Vectors.toHex(MatchId.derive(a, b)), name + ": identifier");
            assertEquals(Vectors.str(c, "match_id_hex"),
                    Vectors.toHex(MatchId.derive(b, a)),
                    name + ": argument order must not matter (SRS-MAT-007)");
            assertEquals(Vectors.str(c, "initiator_on_race_hex"),
                    Vectors.toHex(MatchId.initiatorOnRace(a, b)), name + ": tiebreak");
        }
    }

    @Test
    void committedTokensReproduceExactly() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "tokens").isEmpty(),
                "a token section with no cases proves nothing");
        for (final JsonElement e : Vectors.arr(root, "tokens")) {
            final JsonObject c = Vectors.obj(e);
            final String name = Vectors.str(c, "name");
            final MatchToken token = MatchToken.derive(Vectors.hex(c, "match_id_hex"));
            assertEquals(Vectors.arr(c, "emoji_indices").get(0).getAsInt(),
                    token.emojiFirstIndex(), name + ": first emoji index");
            assertEquals(Vectors.arr(c, "emoji_indices").get(1).getAsInt(),
                    token.emojiSecondIndex(), name + ": second emoji index");
            assertEquals(Vectors.i(c, "color_index"), token.colorIndex(),
                    name + ": colour index");
            assertEquals(Vectors.arr(c, "emoji").get(0).getAsString(),
                    token.emoji()[0], name + ": first glyph");
            assertEquals(Vectors.arr(c, "emoji").get(1).getAsString(),
                    token.emoji()[1], name + ": second glyph");
            assertEquals(Vectors.str(c, "color_name"), token.colorName(), name + ": name");
            assertEquals(Vectors.str(c, "color_hex"), token.colorHex(), name + ": hex");
        }
    }

    @Test
    void committedTimelinesReplayStepForStep() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "scripts").isEmpty(),
                "an empty script set proves nothing");
        for (final JsonElement s : Vectors.arr(root, "scripts")) {
            final JsonObject script = Vectors.obj(s);
            final String name = Vectors.str(script, "name");
            MatchMachine.State state = MatchMachine.State.NONE;
            int i = 0;
            for (final JsonElement st : Vectors.arr(script, "steps")) {
                final JsonObject step = Vectors.obj(st);
                final MatchMachine.Event event =
                        MatchMachine.eventsByName().get(Vectors.str(step, "event"));
                final MatchMachine.Result result = MatchMachine.step(state, event);
                assertEquals(Vectors.str(step, "expect_state"),
                        MatchMachine.stateNames().get(result.state()),
                        name + " step " + i + ": state");
                assertEquals(Vectors.str(step, "expect_effect"),
                        MatchMachine.effectNames().get(result.effect()),
                        name + " step " + i + ": effect");
                state = result.state();
                i++;
            }
        }
    }

    @Test
    void theFamilyKeepsTheSilenceAndTheSingleDoorOnThisPlatformToo() {
        final JsonObject root = Vectors.load(FILE);
        for (final JsonElement s : Vectors.arr(root, "scripts")) {
            final JsonObject script = Vectors.obj(s);
            for (final JsonElement st : Vectors.arr(script, "steps")) {
                final JsonObject step = Vectors.obj(st);
                final String event = Vectors.str(step, "event");
                final String effect = Vectors.str(step, "expect_effect");
                if (event.equals("local_decline") || event.equals("local_ignore")) {
                    assertEquals("none", effect,
                            "a decline/ignore step carries an effect (SRS-MAT-004)");
                }
                if (effect.contains("establish")) {
                    assertTrue(event.equals("local_accept") || event.equals("remote_accept"),
                            "an establishing effect without an acceptance (SRS-MAT-005)");
                }
            }
        }
    }
}

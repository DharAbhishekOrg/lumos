/*
 * Lumos - android-app/protocol-conformance / The delivery family, replayed on Java.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-014, TRS-PAT-002, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Ticks, timelines and receipt policy from the committed
 * chat/delivery_states.json, plus the guardrail re-asserted over the whole
 * state x event product on this platform: nothing but a recipient receipt
 * ever reaches DELIVERED or READ.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.chat.DeliveryMachine;
import org.junit.jupiter.api.Test;

/** Holds the Java delivery mirror to the committed golden vectors. */
public class DeliveryConformanceTest {

    private static final String FILE = "test-vectors/chat/delivery_states.json";

    private static DeliveryMachine.State stateNamed(final String name) {
        for (final var e : DeliveryMachine.stateNames().entrySet()) {
            if (e.getValue().equals(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    @Test
    void committedTicksMatch() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "ticks").isEmpty(), "an empty tick table proves nothing");
        for (final JsonElement e : Vectors.arr(root, "ticks")) {
            final JsonObject c = Vectors.obj(e);
            final DeliveryMachine.State state = stateNamed(Vectors.str(c, "state"));
            assertNotNull(state, "unknown state " + Vectors.str(c, "state"));
            assertEquals(Vectors.str(c, "tick"), DeliveryMachine.tickOf(state).vectorName(),
                    Vectors.str(c, "state") + ": tick");
        }
    }

    @Test
    void committedTimelinesReplayStepForStep() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "scripts").isEmpty(), "an empty script set proves nothing");
        for (final JsonElement s : Vectors.arr(root, "scripts")) {
            final JsonObject script = Vectors.obj(s);
            final String name = Vectors.str(script, "name");
            DeliveryMachine.State state = DeliveryMachine.State.QUEUED;
            int i = 0;
            for (final JsonElement st : Vectors.arr(script, "steps")) {
                final JsonObject step = Vectors.obj(st);
                final DeliveryMachine.Event event =
                        DeliveryMachine.eventsByName().get(Vectors.str(step, "event"));
                assertNotNull(event, name + " step " + i + ": unknown event");
                state = DeliveryMachine.step(state, event);
                assertEquals(Vectors.str(step, "expect_state"),
                        DeliveryMachine.stateNames().get(state), name + " step " + i + ": state");
                assertEquals(Vectors.str(step, "expect_tick"),
                        DeliveryMachine.tickOf(state).vectorName(), name + " step " + i + ": tick");
                i++;
            }
        }
    }

    @Test
    void committedReceiptPolicyMatches() {
        final JsonObject root = Vectors.load(FILE);
        for (final JsonElement e : Vectors.arr(root, "receipt_policy")) {
            final JsonObject c = Vectors.obj(e);
            final DeliveryMachine.Receipts r = DeliveryMachine.receiptsToSend(
                    c.get("read_receipts_enabled").getAsBoolean(), c.get("opened").getAsBoolean());
            assertEquals(c.get("send_delivery").getAsBoolean(), r.delivery(), "delivery receipt");
            assertEquals(c.get("send_read").getAsBoolean(), r.read(), "read receipt");
        }
    }

    @Test
    void onlyTheRecipientCanProduceDeliveredOrReadOnThisPlatform() {
        for (final DeliveryMachine.State s : DeliveryMachine.State.values()) {
            for (final DeliveryMachine.Event e : DeliveryMachine.Event.values()) {
                final DeliveryMachine.State next = DeliveryMachine.step(s, e);
                final boolean entered = next != s
                        && (next == DeliveryMachine.State.DELIVERED
                        || next == DeliveryMachine.State.READ);
                if (entered) {
                    assertTrue(e == DeliveryMachine.Event.DELIVERY_RECEIPT
                            || e == DeliveryMachine.Event.READ_RECEIPT,
                            s + " + " + e + " reached " + next
                                    + " without a recipient receipt (EDP WP-05 guardrail)");
                }
            }
        }
    }
}

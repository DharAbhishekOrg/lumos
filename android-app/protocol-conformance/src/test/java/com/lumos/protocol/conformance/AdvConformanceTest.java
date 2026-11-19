/*
 * Lumos - protocol-conformance / The shared Adv class against the adv vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-009, TRS-PROTO-017, TRS-TST-001, TRS-PROTO-015
 * ADRs: ADR-002, ADR-004
 * Threading: any
 * Notes: CryptoConformanceTest.advertisement() recomputes the schedule from raw
 * primitives and stays as the independent check of the KDF chain itself. This
 * test holds the SHARED com.lumos.protocol.adv.Adv class - the one the Android
 * scan and advertise controllers actually call (WP-03 S2b) - to the same
 * committed file: build reproduces every scheduled payload byte for byte,
 * parse/verify accept them, every negative is rejected with the stable code,
 * and the scan response matches. The S2a lesson applied to the hint layer:
 * conformance over a private copy proves nothing about what the radio sends.
 */
package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.adv.Adv;
import com.lumos.protocol.wire.Frame;
import org.junit.jupiter.api.Test;

/** Holds the shared advertisement hint code to the committed golden vectors. */
public class AdvConformanceTest {

    private static final String FILE = "test-vectors/adv/ble_advertisement.json";

    @Test
    void scheduleReproducesThroughTheSharedClass() throws Frame.WireException {
        final JsonObject root = Vectors.load(FILE);
        final byte[] kAdv = Vectors.hex(root, "k_adv_hex");
        final int rotation = Vectors.i(root, "eid_rotation_sec");
        assertFalse(Vectors.arr(root, "schedule").isEmpty(), "an empty schedule proves nothing");

        for (final JsonElement e : Vectors.arr(root, "schedule")) {
            final JsonObject c = Vectors.obj(e);
            final long epoch = Vectors.l(c, "epoch");
            assertEquals(epoch, Adv.epoch(Vectors.l(c, "unix_seconds"), rotation),
                    "epoch arithmetic");

            assertArrayEquals(Vectors.hex(c, "eid_hex"), Adv.deriveEid(kAdv, epoch), "eid");
            assertEquals(Vectors.str(c, "adv_v1_hex"),
                    Vectors.toHex(Adv.build(kAdv, Vectors.i(c, "mode_flags"), epoch)),
                    "adv_v1 must build to the committed bytes");

            final Adv.Payload verified = Adv.verify(kAdv, Vectors.hex(c, "adv_v1_hex"), epoch);
            assertArrayEquals(Vectors.hex(c, "eid_hex"), verified.eid(), "verified eid");
            assertArrayEquals(Vectors.hex(c, "auth_tag_hex"), verified.authTag(), "verified tag");
            assertEquals(Vectors.str(c, "scan_response_hex"),
                    Vectors.toHex(Adv.buildScanResponse(
                            Vectors.i(root, "capability_bits"), Vectors.l(root, "venue_hint"))),
                    "scan response");
        }
    }

    @Test
    void everyNegativeIsRejectedWithTheStableCode() {
        final JsonObject root = Vectors.load(FILE);
        final byte[] kAdv = Vectors.hex(root, "k_adv_hex");
        assertFalse(Vectors.arr(root, "negatives").isEmpty(), "a family without negatives proves nothing");

        for (final JsonElement e : Vectors.arr(root, "negatives")) {
            final JsonObject c = Vectors.obj(e);
            final String name = Vectors.str(c, "name");
            try {
                Adv.verify(kAdv, Vectors.hex(c, "adv_v1_hex"), Vectors.l(c, "epoch"));
                fail(name + ": a hint that must be discarded verified");
            } catch (final Frame.WireException ex) {
                assertEquals(Vectors.str(c, "expected_error"), ex.code(), name + ": stable code");
            }
        }
    }
}

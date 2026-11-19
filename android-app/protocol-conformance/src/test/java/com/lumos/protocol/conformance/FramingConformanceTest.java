/*
 * Lumos - protocol-conformance / Framing and error-registry conformance.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-PROTO-001, TRS-PROTO-006, TRS-PROTO-013, TRS-PROTO-015, SRS-NFR-CMP-004
 * ADRs: ADR-007
 * Threading: any
 * Notes: Conformance is exact. Positive vectors must reproduce the committed
 * bytes byte-for-byte; negative vectors must fail with the exact stable code,
 * not merely fail. The Java codec was written independently against TRS Sec. 9.1,
 * so agreement here is genuine cross-language agreement rather than a shared
 * implementation agreeing with itself.
 */
package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.wire.Frame;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Framing round-trip, negative and error-registry conformance. */
final class FramingConformanceTest {

    @Test
    /** every positive framing vector re-encodes to the exact committed bytes */
    void frameRoundtrip() throws Exception {
        final JsonObject root = Vectors.load("test-vectors/framing/frame_roundtrip.json");
        final Set<Integer> covered = new HashSet<>();
        int cases = 0;

        for (final JsonElement el : Vectors.arr(root, "cases")) {
            final JsonObject c = Vectors.obj(el);
            final JsonObject f = c.getAsJsonObject("fields");
            final String name = Vectors.str(c, "name");
            final boolean decodeOnly = Vectors.b(c, "decode_only");

            final Frame.Header h = new Frame.Header();
            h.version = Vectors.i(f, "version");
            h.type = Vectors.i(f, "message_type");
            h.flags = Vectors.i(f, "flags");
            h.sessionId = Vectors.hex(f, "session_id_hex");
            h.messageId = Vectors.hex(f, "message_id_hex");
            h.sequence = Vectors.l(f, "sequence");
            h.ackBase = Vectors.l(f, "ack_base");
            h.expirySeconds = Vectors.l(f, "expiry_seconds");
            h.hopLimit = Vectors.i(f, "hop_limit");
            final byte[] payload = Vectors.hex(f, "payload_hex");

            if (!decodeOnly) {
                final byte[] encoded = Frame.encode(h, payload);
                assertEquals(Vectors.str(c, "frame_hex"), Vectors.toHex(encoded),
                        "encoded bytes differ for " + name);
                covered.add(h.type);
                cases++;
            }

            final Frame decoded = Frame.decode(Vectors.hex(c, "frame_hex"));
            assertEquals(h.version, decoded.header().version, name + " version");
            assertEquals(h.type, decoded.header().type, name + " type");
            assertEquals(h.flags, decoded.header().flags, name + " flags");
            assertEquals(Vectors.toHex(h.sessionId), Vectors.toHex(decoded.header().sessionId), name + " session");
            assertEquals(Vectors.toHex(h.messageId), Vectors.toHex(decoded.header().messageId), name + " message");
            assertEquals(h.sequence, decoded.header().sequence, name + " sequence");
            assertEquals(h.ackBase, decoded.header().ackBase, name + " ackBase");
            assertEquals(h.expirySeconds, decoded.header().expirySeconds, name + " expiry");
            assertEquals(h.hopLimit, decoded.header().hopLimit, name + " hopLimit");
            assertEquals(Vectors.toHex(payload), Vectors.toHex(decoded.payload()), name + " payload");
            assertEquals(Vectors.i(f, "payload_length"), decoded.payload().length, name + " payload length");

            final boolean known = Vectors.b(c.getAsJsonObject("expect"), "message_type_known");
            assertEquals(known, decoded.header().messageClass() != null, name + " message_type_known");
        }

        assertTrue(cases > 0, "no positive framing cases");
        for (final MessageClass mc : MessageClass.values()) {
            assertTrue(covered.contains(mc.code()),
                    "message class " + mc + " has no positive roundtrip vector (TRS-PROTO-014)");
        }
    }

    @Test
    /** every negative framing vector fails with its exact stable code */
    void frameInvalid() {
        final JsonObject root = Vectors.load("test-vectors/framing/frame_invalid.json");
        int cases = 0;

        for (final JsonElement el : Vectors.arr(root, "cases")) {
            final JsonObject c = Vectors.obj(el);
            final String name = Vectors.str(c, "name");
            final String expected = Vectors.str(c, "expected_error");
            try {
                Frame.decode(Vectors.hex(c, "frame_hex"));
                fail("decoder ACCEPTED negative vector " + name + "; must fail with " + expected);
            } catch (Frame.WireException e) {
                assertEquals(expected, e.code(), "wrong stable code for " + name);
                assertTrue(LumosErrors.known(e.code()), e.code() + " is not in the stable registry");
            }
            cases++;
        }
        assertTrue(cases > 0, "no negative framing cases");
    }

    @Test
    /** the committed error registry matches the generated Java registry */
    void errorRegistry() {
        final JsonObject root = Vectors.load("test-vectors/errors/error_codes.json");
        int cases = 0;
        for (final JsonElement el : Vectors.arr(root, "codes")) {
            final JsonObject c = Vectors.obj(el);
            final String code = Vectors.str(c, "code");
            assertTrue(LumosErrors.known(code), code + " missing from the generated Java registry");
            assertEquals(Vectors.i(c, "numeric"), LumosErrors.NUMERIC.get(code).intValue(),
                    "numeric drift for " + code);
            cases++;
        }
        assertEquals(LumosErrors.NUMERIC.size(), cases,
                "the generated registry and the committed snapshot differ in size");
    }

    @Test
    /** the authenticated header is exactly the first HEADER_LEN bytes */
    void headerBytesIsTheAad() throws Exception {
        final JsonObject root = Vectors.load("test-vectors/framing/frame_roundtrip.json");
        final JsonObject first = Vectors.obj(Vectors.arr(root, "cases").get(0));
        final byte[] encoded = Vectors.hex(first, "frame_hex");
        final byte[] aad = Frame.headerBytes(encoded);
        assertEquals(Frame.HEADER_LEN, aad.length);
        assertTrue(Vectors.toHex(encoded).startsWith(Vectors.toHex(aad)));
    }

    @Test
    /** unknown non-critical classes decode and are skippable */
    void unknownNonCriticalIsSkippable() {
        final JsonObject root = Vectors.load("test-vectors/framing/frame_roundtrip.json");
        boolean sawSkippable = false;
        for (final JsonElement el : Vectors.arr(root, "cases")) {
            final JsonObject c = Vectors.obj(el);
            if (!"unknown_type_noncritical_skippable".equals(Vectors.str(c, "name"))) {
                continue;
            }
            sawSkippable = true;
            try {
                final Frame f = Frame.decode(Vectors.hex(c, "frame_hex"));
                assertNull(f.header().messageClass(), "class should be unknown");
                assertNotNull(f.payload());
            } catch (Frame.WireException e) {
                fail("a non-critical unknown class must decode, not fail: " + e.code());
            }
        }
        assertTrue(sawSkippable, "the skippable TRS-PROTO-006 case is missing from the vectors");
    }
}

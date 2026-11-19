/*
 * Lumos - protocol-conformance / Java conformance to the fragmentation vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-010, TRS-PROTO-015, TRS-TST-001
 * ADRs: ADR-004
 * Threading: any
 * Notes: The Java quarter of the fragmentation family. split cases are exact:
 * Fragmenter.split must reproduce the committed fragment bytes, and the
 * committed fragments must reassemble to the committed blob. The scripted
 * timelines then drive FragmentReassembler offer by offer with the file's own
 * millisecond clock - reassembly timeout, duplicate suppression, gaps and
 * length mismatches all land as exact outcomes or exact stable codes, so a
 * drift from the Go reference's semantics fails here as a named step, not as a
 * flaky radio bug on a device.
 */
package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.wire.Fragmenter;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.FragmentReassembler;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Holds the shared fragmentation layer to the committed golden vectors. */
public class FragmentationConformanceTest {

    private static final String FILE = "test-vectors/framing/fragmentation.json";

    @Test
    void splitVectorsReproduceByteForByte() throws Frame.WireException {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "split").isEmpty(), "an empty split set proves nothing");
        for (final JsonElement e : Vectors.arr(root, "split")) {
            final JsonObject c = Vectors.obj(e);
            final String name = Vectors.str(c, "name");
            final byte[] blob = Vectors.hex(c, "blob_hex");
            final List<byte[]> fragments = Fragmenter.split(
                    blob, Vectors.i(c, "assembly_id"), Vectors.i(c, "chunk_payload"));
            assertEquals(Vectors.arr(c, "fragments_hex").size(), fragments.size(),
                    name + ": fragment count");
            for (int i = 0; i < fragments.size(); i++) {
                assertEquals(Vectors.arr(c, "fragments_hex").get(i).getAsString(),
                        Vectors.toHex(fragments.get(i)),
                        name + ": fragment " + i + " must match the committed bytes");
            }

            // The committed fragments must also reassemble to the committed blob.
            final FragmentReassembler r = new FragmentReassembler(1);
            byte[] out = null;
            for (final JsonElement fh : Vectors.arr(c, "fragments_hex")) {
                final FragmentReassembler.Offer offer =
                        r.offer(Vectors.hexToBytes(fh.getAsString()), 0);
                if (offer.outcome() == FragmentReassembler.Outcome.COMPLETE) {
                    out = offer.payload();
                }
            }
            assertArrayEquals(blob, out, name + ": committed fragments must reassemble to the blob");
        }
    }

    @Test
    void scriptedTimelinesPlayOutExactly() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "scripts").isEmpty(), "an empty script set proves nothing");
        for (final JsonElement e : Vectors.arr(root, "scripts")) {
            final JsonObject script = Vectors.obj(e);
            runScript(Vectors.str(script, "name"),
                    new FragmentReassembler(Vectors.l(script, "timeout_millis")), script);
        }
    }

    private void runScript(final String name, final FragmentReassembler r, final JsonObject script) {
        int i = 0;
        for (final JsonElement se : Vectors.arr(script, "steps")) {
            final JsonObject step = Vectors.obj(se);
            final String at = name + " step " + i;
            final String expect = Vectors.str(step, "expect");
            try {
                final FragmentReassembler.Offer offer =
                        r.offer(Vectors.hex(step, "fragment_hex"), Vectors.l(step, "at_millis"));
                if ("error".equals(expect)) {
                    fail(at + ": expected " + Vectors.str(step, "error") + ", offer succeeded");
                }
                assertEquals(expect, outcomeName(offer.outcome()), at + ": outcome");
                assertEquals(Vectors.b(step, "restarted"), offer.restarted(), at + ": restarted");
                assertEquals(Vectors.b(step, "expired_previous"), offer.expiredPrevious(),
                        at + ": expired_previous");
                if ("complete".equals(expect)) {
                    assertEquals(Vectors.str(step, "payload_hex"), Vectors.toHex(offer.payload()),
                            at + ": reassembled payload");
                }
            } catch (final Frame.WireException ex) {
                if (!"error".equals(expect)) {
                    fail(at + ": unexpected " + ex.code());
                }
                assertEquals(Vectors.str(step, "error"), ex.code(), at + ": stable code");
            }
            i++;
        }
    }

    private static String outcomeName(final FragmentReassembler.Outcome outcome) {
        switch (outcome) {
            case ACCEPTED: return "accepted";
            case DUPLICATE: return "duplicate";
            case COMPLETE: return "complete";
            default: throw new AssertionError("unreachable: " + outcome);
        }
    }
}

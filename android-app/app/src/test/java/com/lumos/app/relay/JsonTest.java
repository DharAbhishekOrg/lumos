/*
 * Lumos - android-app/app / The relay JSON codec accepts the contract and refuses everything else.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, SRS-RMT-009, TRS-TST-001
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: Each refusal is one line of the codec's strictness; each is checked
 * on its own so a loosened rule fails by name. The writer is checked by
 * reading its output back with gson, an independent parser.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The strict codec. */
public class JsonTest {

    @Test
    void theContractShapesRead() throws Exception {
        final Json.Obj o = Json.parseObject(" {\"a\":\"x\\\"y\\u0041\\n\", \"n\": -12, \"t\": true, \"f\": false,"
                + " \"z\": null, \"o\": {\"k\": 0}, \"l\": [{\"m\": 1}, {\"m\": 2}], \"unknown\": [1, \"s\"]} ");
        assertEquals("x\"yA\n", o.string("a"));
        assertEquals(-12, o.integer("n"));
        assertTrue(o.bool("t"));
        assertFalse(o.bool("f"));
        assertNull(o.optObject("z"), "null reads as absent");
        assertNull(o.optObject("missing"));
        assertEquals(0, o.object("o").integer("k"));
        assertEquals(2, o.objects("l").get(1).integer("m"));
        assertTrue(o.has("unknown"), "an unknown field is kept and ignored, so a newer server can add one");
        assertEquals(Long.MAX_VALUE, Json.parseObject("{\"x\":" + Long.MAX_VALUE + "}").integer("x"));
    }

    @Test
    void everythingElseIsRefused() {
        final StringBuilder deep = new StringBuilder();
        for (int i = 0; i <= Json.MAX_DEPTH + 1; i++) {
            deep.append("{\"a\":");
        }
        deep.append('1');
        for (int i = 0; i <= Json.MAX_DEPTH + 1; i++) {
            deep.append('}');
        }
        final List<String> refused = List.of(
                "", "[]", "\"top\"", "{", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "{\"a\":1} x", "{\"a\":1}{}",
                "{\"a\":1,\"a\":2}", "{\"a\":1.5}", "{\"a\":1e3}", "{\"a\":01}", "{\"a\":-}", "{\"a\":+1}",
                "{\"a\":99999999999999999999}", "{\"a\":\"\u0001\"}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}",
                "{\"a\":\"\\u١٢٣٤\"}", "{\"a\":tru}", "{\"a\":nul}", "{\"a\":NaN}", "{\"a\":\"open}", deep.toString());
        for (final String s : refused) {
            assertThrows(Json.MalformedException.class, () -> Json.parseObject(s), "must refuse: " + s);
        }
    }

    @Test
    void aFieldOfTheWrongTypeIsRefusedWhereItIsRead() throws Exception {
        final Json.Obj o = Json.parseObject("{\"s\":\"x\",\"n\":1,\"a\":[1]}");
        assertThrows(Json.MalformedException.class, () -> o.integer("s"));
        assertThrows(Json.MalformedException.class, () -> o.string("n"));
        assertThrows(Json.MalformedException.class, () -> o.bool("n"));
        assertThrows(Json.MalformedException.class, () -> o.object("n"));
        assertThrows(Json.MalformedException.class, () -> o.objects("a"), "an array of non-objects");
        assertThrows(Json.MalformedException.class, () -> o.string("absent"));
    }

    @Test
    void whatTheWriterWritesAnotherParserReadsBack() {
        final String tricky = "q\"b\\s/\u0000\u001f\u007f é";
        final String out = Json.object().put("s", tricky).put("n", -7).putRaw("a", Json.stringArray(List.of("x", "y")))
                .putRaw("o", Json.array(List.of(Json.object().put("k", 1).end()))).end();
        final JsonObject g = JsonParser.parseString(out).getAsJsonObject();
        assertEquals(tricky, g.get("s").getAsString());
        assertEquals(-7, g.get("n").getAsLong());
        assertEquals("y", g.getAsJsonArray("a").get(1).getAsString());
        assertEquals(1, g.getAsJsonArray("o").get(0).getAsJsonObject().get("k").getAsInt());
        assertEquals("{}", Json.object().end());
    }

    @Test
    void hexIsStrictAsciiOfExactlyTheRightLength() throws Exception {
        assertEquals("00ff10ab", Hex.encode(new byte[] {0, (byte) 0xFF, 0x10, (byte) 0xAB}));
        assertEquals((byte) 0xAB, Hex.decode("AB", 1)[0], "upper case is read");
        for (final String bad : List.of("0", "000", "zz", "١٢", " 0")) {
            assertThrows(Json.MalformedException.class, () -> Hex.decode(bad, 1), "must refuse: " + bad);
        }
    }
}

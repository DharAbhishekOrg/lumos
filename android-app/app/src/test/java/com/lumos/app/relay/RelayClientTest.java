/*
 * Lumos - android-app/app / The client sends the contract's shapes and sorts every answer the same way.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, TRS-BCK-007, SRS-RMT-003, SRS-RMT-009, TRS-TST-001
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: A scripted transport answers with exactly one status and body, so
 * each mapping is pinned on its own. What the client sends is read back
 * with gson and compared field by field against backend/internal/api.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.x3dh.X3dh;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The typed client. */
public class RelayClientTest {

    private static final SecureRandom RNG = new SecureRandom();

    private static byte[] random(final int n) {
        final byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** A transport that records each request and answers from a script. */
    private static final class Scripted implements HttpTransport {
        private final List<String[]> requests = new ArrayList<>();
        private Response answer;
        private boolean offline;

        Scripted answer(final int status, final String body, final long retryAfter) {
            answer = new Response(status, body, retryAfter);
            return this;
        }

        @Override
        public Response exchange(final String method, final String path, final String bearer, final String body)
                throws IOException {
            requests.add(new String[] {method, path, bearer, body});
            if (offline) {
                throw new IOException("offline");
            }
            return answer;
        }

        String[] last() {
            return requests.get(requests.size() - 1);
        }
    }

    private static RelayTypes.Send aSend() {
        return new RelayTypes.Send(random(16), random(16), MessageClass.TEXT_MESSAGE.code(), random(32), random(100));
    }

    private static RelayException submitAgainst(final Scripted t) {
        return assertThrows(RelayException.class, () -> RelayClient.anonymous(t).as(random(32)).submit(aSend()));
    }

    @Test
    void everyAnswerIsSortedByWhatTheCallerDoesNext() {
        final Object[][] table = {
            {401, "{\"code\":\"ERR_AUTH_REJECTED\"}", RelayException.Kind.AUTH},
            {429, "{\"code\":\"ERR_RELAY_RATE_LIMITED\"}", RelayException.Kind.BUSY},
            {500, "{\"code\":\"INTERNAL\"}", RelayException.Kind.BUSY},
            {503, "<html>gateway</html>", RelayException.Kind.BUSY},
            {400, "{\"code\":\"ERR_RELAY_MAILBOX_FULL\"}", RelayException.Kind.BUSY},
            {400, "{\"code\":\"ERR_PAYLOAD_MALFORMED\"}", RelayException.Kind.REFUSED},
            {400, "{\"code\":\"ERR_CRYPTO_REPLAY\"}", RelayException.Kind.REFUSED},
            {413, "", RelayException.Kind.REFUSED},
            {404, "{\"code\":\"NOT_FOUND\"}", RelayException.Kind.NOT_FOUND},
            {200, "", RelayException.Kind.REFUSED},
        };
        for (final Object[] row : table) {
            final RelayException e = submitAgainst(new Scripted().answer((Integer) row[0], (String) row[1], -1));
            assertEquals(row[2], e.kind(), "status " + row[0] + " " + row[1]);
        }
        final Scripted off = new Scripted();
        off.offline = true;
        final RelayException e = submitAgainst(off);
        assertEquals(RelayException.Kind.OFFLINE, e.kind());
        assertTrue(e.retryable());
        assertEquals(17, submitAgainst(new Scripted().answer(429, "", 17)).retryAfterSeconds());
        assertFalse(submitAgainst(new Scripted().answer(400, "", -1)).retryable());
    }

    @Test
    void anErrorCodeIsKeptOnlyWhenItLooksLikeOne() {
        assertEquals(LumosErrors.ERR_CRYPTO_REPLAY,
                submitAgainst(new Scripted().answer(400, "{\"code\":\"ERR_CRYPTO_REPLAY\"}", -1)).code());
        assertEquals("", submitAgainst(new Scripted().answer(400, "{\"code\":\"<b>hi</b>\"}", -1)).code(),
                "server text never reaches diagnostics verbatim");
        assertEquals("", submitAgainst(new Scripted().answer(400, "not json", -1)).code());
    }

    @Test
    void aSuccessThatDoesNotParseIsBusyNeverACrash() {
        final String rid = Hex.encode(random(16));
        final List<String> badFetches = List.of("not json", "{}", "{\"envelopes\":[{\"message_id\":\"00\"}]}",
                "{\"envelopes\":[" + envelope(Hex.encode(random(16)), 300, "AAAA") + "]}",
                "{\"envelopes\":[" + envelope(Hex.encode(random(16)), 16, "***") + "]}", elevenEnvelopes());
        for (final String body : badFetches) {
            final RelayException e = assertThrows(RelayException.class,
                    () -> RelayClient.anonymous(new Scripted().answer(200, body, -1)).as(random(32)).fetch());
            assertEquals(RelayException.Kind.BUSY, e.kind(), body);
            assertEquals(LumosErrors.ERR_PAYLOAD_MALFORMED, e.code());
        }
        assertThrows(RelayException.class, () -> RelayClient.anonymous(
                new Scripted().answer(201, "{\"routing_id\":\"" + rid + "\",\"credential\":\"00\"}", -1))
                .register(random(32), random(32), random(64)));
    }

    private static String envelope(final String id, final int cls, final String payload) {
        return "{\"message_id\":\"" + id + "\",\"class\":" + cls + ",\"sender_proof\":\"" + Hex.encode(random(32))
                + "\",\"created_at\":1,\"expires_at\":2,\"payload\":\"" + payload + "\"}";
    }

    private static String elevenEnvelopes() {
        final List<String> e = new ArrayList<>();
        for (int i = 0; i <= RelayClient.FETCH_BATCH; i++) {
            e.add(envelope(Hex.encode(random(16)), 16, "AAAA"));
        }
        return "{\"envelopes\":[" + String.join(",", e) + "]}";
    }

    @Test
    void anUnknownPeerIsAnEmptyClaimNotAnError() throws Exception {
        final Scripted t = new Scripted().answer(404, "{\"code\":\"NOT_FOUND\"}", -1);
        assertFalse(RelayClient.anonymous(t).as(random(32)).claim(random(16)).isPresent());
    }

    @Test
    void theRequestsAreTheShapesTheBackendReads() throws Exception {
        final Scripted t = new Scripted();
        final RelayClient anon = RelayClient.anonymous(t);
        final byte[] cred = random(32);
        final RelayClient me = anon.as(cred);

        t.answer(200, "{\"challenge\":\"" + Hex.encode(random(32)) + "\"}", -1);
        anon.challenge();
        assertEquals("POST", t.last()[0]);
        assertEquals("/v1/auth/challenge", t.last()[1]);
        assertNull(t.last()[2], "no credential before registering");

        final RelayTypes.Send s = aSend();
        t.answer(202, "", -1);
        me.submit(s);
        assertEquals("/v1/envelopes", t.last()[1]);
        assertEquals(Hex.encode(cred), t.last()[2]);
        final JsonObject sub = JsonParser.parseString(t.last()[3]).getAsJsonObject();
        assertEquals(Hex.encode(s.recipient()), sub.get("recipient").getAsString());
        assertEquals(Hex.encode(s.messageId()), sub.get("message_id").getAsString());
        assertEquals(MessageClass.TEXT_MESSAGE.code(), sub.get("class").getAsInt());
        assertEquals(Hex.encode(s.capability()), sub.get("capability").getAsString());
        assertArrayEquals(s.payload(), Base64.getDecoder().decode(sub.get("payload").getAsString()));
        assertEquals(5, sub.size(), "nothing else is sent");

        t.answer(200, "{\"envelopes\":[]}", -1);
        me.fetch();
        assertEquals("GET", t.last()[0]);
        assertEquals("/v1/envelopes?limit=" + RelayClient.FETCH_BATCH, t.last()[1]);

        final byte[] id = random(16);
        t.answer(200, "{\"acknowledged\":1}", -1);
        assertEquals(1, me.ack(List.of(id)));
        final JsonArray ids = JsonParser.parseString(t.last()[3]).getAsJsonObject().getAsJsonArray("message_ids");
        assertEquals(Hex.encode(id), ids.get(0).getAsString());

        final byte[] proof = random(32);
        t.answer(204, "", -1);
        me.allow(proof);
        assertEquals("PUT", t.last()[0]);
        assertEquals("/v1/mailbox/capabilities", t.last()[1]);
        me.revoke(proof);
        assertEquals("DELETE", t.last()[0]);
        assertEquals("/v1/mailbox/capabilities/" + Hex.encode(proof), t.last()[1]);

        final X3dh.Bundle b = new X3dh.Bundle(random(32), random(32), random(64), 7, random(32), random(64), 0, null);
        me.publishPrekeys(b, List.of(new RelayTypes.OneTimeKey(9, random(32))));
        final JsonObject pub = JsonParser.parseString(t.last()[3]).getAsJsonObject();
        assertEquals(Hex.encode(b.identityDhSig()), pub.getAsJsonObject("bundle").get("identity_dh_sig").getAsString());
        assertEquals(7, pub.getAsJsonObject("bundle").get("spk_id").getAsLong());
        assertEquals(9, pub.getAsJsonArray("one_time").get(0).getAsJsonObject().get("key_id").getAsLong());
        me.publishPrekeys(null, List.of(new RelayTypes.OneTimeKey(10, random(32))));
        assertFalse(JsonParser.parseString(t.last()[3]).getAsJsonObject().has("bundle"), "replenishment sends no bundle");
    }

    @Test
    void theLargestPossibleFetchFitsTheTransportCap() {
        final int max = LumosConstants.RELAY_TEXT_ENVELOPE_MAX_BYTES_MAX;
        final List<String> e = new ArrayList<>();
        for (int i = 0; i < RelayClient.FETCH_BATCH; i++) {
            e.add(envelope(Hex.encode(random(16)), 255, Base64.getEncoder().encodeToString(new byte[max]))
                    .replace("\"created_at\":1,\"expires_at\":2", "\"created_at\":" + Long.MAX_VALUE
                            + ",\"expires_at\":" + Long.MAX_VALUE));
        }
        final int real = ("{\"envelopes\":[" + String.join(",", e) + "]}\n").length();
        assertTrue(real <= RelayClient.largestFetchBody(), real + " > estimate " + RelayClient.largestFetchBody());
        assertTrue(RelayClient.largestFetchBody() <= UrlConnectionTransport.MAX_RESPONSE_BYTES,
                "a full batch of the largest envelopes the register allows must fit the response cap");
    }
}

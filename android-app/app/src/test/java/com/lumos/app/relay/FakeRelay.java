/*
 * Lumos - android-app/app / An in-memory registry and relay with the S5 semantics, and a way to kill the caller.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-TST-006
 * ADRs: ADR-001
 * Threading: test-only
 * Notes: Mirrors backend/internal/registry and backend/internal/relay as far
 * as the client can observe them: one challenge per registration, Ed25519
 * over DEVICE_AUTH_SIGNING_INFO | challenge | key, one 401 code for every
 * auth failure, one-time prekeys never handed out twice, a send on an
 * unknown or revoked capability answered 202 and dropped, a retry of a
 * stored id with the same bytes answered 202 and not stored twice, the same
 * id with other bytes refused as a replay, the per-mailbox quota, TTL
 * expiry, oldest-first fetch and ack-to-delete. It parses with gson, not the
 * app's codec, so a codec bug cannot hide behind a matching bug here. The
 * real cross-stack run against lumosd is WP-05 S8's.
 *
 * Faults are scripted per "METHOD path" prefix: OFFLINE throws IOException (no
 * response), DIE_BEFORE throws ProcessDeath before the relay changes
 * anything, DIE_AFTER applies the request and then throws ProcessDeath, as if
 * the process were killed while the response was on its way.
 */

package com.lumos.app.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumos.app.Fixtures;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.wire.LumosCrypto;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An in-memory relay. */
final class FakeRelay implements HttpTransport {

    /** The process was killed: everything in memory is gone. */
    static final class ProcessDeath extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProcessDeath() {
            super("process killed");
        }
    }

    /** A scripted fault. */
    enum Fault { OFFLINE, DIE_BEFORE, DIE_AFTER }

    static final long TTL_SECONDS = 7L * 24 * 3600;
    private static final SecureRandom RNG = new SecureRandom();

    private final Fixtures.TestClock clock;
    private final Set<String> challenges = new HashSet<>();
    private final Map<String, String> deviceByCredential = new HashMap<>();
    private final Set<String> revoked = new HashSet<>();
    private final Map<String, JsonObject> bundles = new HashMap<>();
    private final Map<String, Deque<JsonObject>> oneTime = new HashMap<>();
    private final Map<String, Set<String>> usedOneTimeIds = new HashMap<>();
    private final Map<String, Set<String>> proofs = new HashMap<>();
    /** recipient -> id -> envelope, insertion (oldest-first) order. */
    private final Map<String, LinkedHashMap<String, JsonObject>> mailboxes = new HashMap<>();
    private final Map<String, Integer> quotas = new HashMap<>();
    private final Deque<String[]> faults = new ArrayDeque<>();
    private long rateLimitRetryAfter = -1;
    private boolean offline;
    private boolean serveExpired;
    private int stored;
    final List<String> log = new ArrayList<>();

    FakeRelay(final Fixtures.TestClock clock) {
        this.clock = clock;
    }

    // ---- scripting ----------------------------------------------------------------------------

    /** Scripts a fault for the next request whose "METHOD path" starts with the prefix. */
    void fault(final String requestPrefix, final Fault f) {
        faults.add(new String[] {requestPrefix, f.name()});
    }

    void setOffline(final boolean off) {
        offline = off;
    }

    void rateLimitNextSend(final long retryAfterSeconds) {
        rateLimitRetryAfter = retryAfterSeconds;
    }

    void quota(final byte[] recipient, final int envelopes) {
        quotas.put(Hex.encode(recipient), envelopes);
    }

    void serveExpired(final boolean on) {
        serveExpired = on;
    }

    void revokeDevice(final byte[] credential) {
        revoked.add(deviceByCredential.get(Hex.encode(credential)));
    }

    int waiting(final byte[] recipient) {
        return mailboxes.getOrDefault(Hex.encode(recipient), new LinkedHashMap<>()).size();
    }

    int storedEver() {
        return stored;
    }

    List<Long> oneTimeIds(final byte[] routingId) {
        final List<Long> out = new ArrayList<>();
        for (final JsonObject k : oneTime.getOrDefault(Hex.encode(routingId), new ArrayDeque<>())) {
            out.add(k.get("key_id").getAsLong());
        }
        return out;
    }

    int oneTimeLeft(final byte[] routingId) {
        return oneTime.getOrDefault(Hex.encode(routingId), new ArrayDeque<>()).size();
    }

    /** Sends one envelope as a hostile or broken sender would, bypassing every client check. */
    void inject(final byte[] recipient, final byte[] messageId, final byte[] senderProof, final int cls,
            final byte[] payload) {
        final JsonObject e = new JsonObject();
        e.addProperty("message_id", Hex.encode(messageId));
        e.addProperty("class", cls);
        e.addProperty("sender_proof", Hex.encode(senderProof));
        e.addProperty("created_at", now());
        e.addProperty("expires_at", now() + TTL_SECONDS);
        e.addProperty("payload", Base64.getEncoder().encodeToString(payload));
        mailboxes.computeIfAbsent(Hex.encode(recipient), k -> new LinkedHashMap<>()).put(Hex.encode(messageId), e);
    }

    private long now() {
        return clock.nowUtcMillis() / 1000;
    }

    // ---- the transport -----------------------------------------------------------------------

    @Override
    public Response exchange(final String method, final String path, final String bearer, final String body)
            throws IOException {
        log.add(method + " " + path.replaceAll("\\?.*", ""));
        if (offline) {
            throw new IOException("offline");
        }
        Fault f = null;
        final String[] next = faults.peek();
        if (next != null && (method + " " + path).startsWith(next[0])) {
            faults.poll();
            f = Fault.valueOf(next[1]);
        }
        if (f == Fault.OFFLINE) {
            throw new IOException("scripted offline");
        }
        if (f == Fault.DIE_BEFORE) {
            throw new ProcessDeath();
        }
        final Response r = handle(method, path, bearer, body);
        if (f == Fault.DIE_AFTER) {
            throw new ProcessDeath();
        }
        return r;
    }

    private static Response json(final int status, final JsonElement body) {
        return new Response(status, body.toString(), -1);
    }

    private static Response empty(final int status) {
        return new Response(status, "", -1);
    }

    private static Response code(final int status, final String code) {
        final JsonObject o = new JsonObject();
        o.addProperty("code", code);
        return json(status, o);
    }

    private Response handle(final String method, final String path, final String bearer, final String body) {
        final String route = method + " " + path.replaceAll("\\?.*", "");
        if ("POST /v1/auth/challenge".equals(route)) {
            final byte[] c = new byte[32];
            RNG.nextBytes(c);
            challenges.add(Hex.encode(c));
            final JsonObject o = new JsonObject();
            o.addProperty("challenge", Hex.encode(c));
            return json(200, o);
        }
        if ("POST /v1/devices".equals(route)) {
            return register(JsonParser.parseString(body).getAsJsonObject());
        }
        final String device = bearer == null ? null : deviceByCredential.get(bearer);
        if (device == null || revoked.contains(device)) {
            return code(401, "ERR_AUTH_REJECTED");
        }
        final JsonObject in = body == null ? null : JsonParser.parseString(body).getAsJsonObject();
        if ("PUT /v1/prekeys".equals(route)) {
            return publish(device, in);
        }
        if ("GET /v1/prekeys/status".equals(route)) {
            final JsonObject o = new JsonObject();
            final int n = oneTime.getOrDefault(device, new ArrayDeque<>()).size();
            o.addProperty("one_time_remaining", n);
            o.addProperty("needs_replenish", n < LumosConstants.PREKEY_REPLENISH_THRESHOLD_DEFAULT);
            return json(200, o);
        }
        if (route.startsWith("POST /v1/prekeys/") && route.endsWith("/claim")) {
            return claim(route.substring("POST /v1/prekeys/".length(), route.length() - "/claim".length()));
        }
        if ("PUT /v1/mailbox/capabilities".equals(route)) {
            proofs.computeIfAbsent(device, k -> new HashSet<>()).add(in.get("proof").getAsString());
            return empty(204);
        }
        if (route.startsWith("DELETE /v1/mailbox/capabilities/")) {
            proofs.getOrDefault(device, new HashSet<>()).remove(route.substring(route.lastIndexOf('/') + 1));
            return empty(204);
        }
        if ("POST /v1/envelopes".equals(route)) {
            return submit(in);
        }
        if ("GET /v1/envelopes".equals(route)) {
            return fetch(device, Integer.parseInt(path.replaceAll(".*limit=", "")));
        }
        if ("POST /v1/envelopes/ack".equals(route)) {
            final LinkedHashMap<String, JsonObject> box = mailboxes.getOrDefault(device, new LinkedHashMap<>());
            int n = 0;
            for (final JsonElement id : in.getAsJsonArray("message_ids")) {
                n += box.remove(id.getAsString()) != null ? 1 : 0;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("acknowledged", n);
            return json(200, o);
        }
        return code(404, "NOT_FOUND");
    }

    private Response register(final JsonObject in) {
        final String ch = in.get("challenge").getAsString();
        final byte[] key = unhex(in.get("device_key").getAsString());
        final byte[] msg = LumosCrypto.concat(
                LumosConstants.DEVICE_AUTH_SIGNING_INFO_VALUE.getBytes(StandardCharsets.UTF_8), unhex(ch), key);
        if (!challenges.remove(ch) || !LumosCrypto.ed25519Verify(key, msg, unhex(in.get("signature").getAsString()))) {
            return code(401, "ERR_AUTH_REJECTED");
        }
        final byte[] rid = new byte[16];
        final byte[] cred = new byte[32];
        RNG.nextBytes(rid);
        RNG.nextBytes(cred);
        deviceByCredential.put(Hex.encode(cred), Hex.encode(rid));
        final JsonObject o = new JsonObject();
        o.addProperty("routing_id", Hex.encode(rid));
        o.addProperty("credential", Hex.encode(cred));
        return json(201, o);
    }

    private Response publish(final String device, final JsonObject in) {
        if (in.has("bundle")) {
            bundles.put(device, in.getAsJsonObject("bundle"));
        } else if (!bundles.containsKey(device)) {
            return code(400, "ERR_PREKEY_UPLOAD_INVALID");
        }
        if (in.has("one_time")) {
            final Set<String> used = usedOneTimeIds.computeIfAbsent(device, k -> new HashSet<>());
            final Deque<JsonObject> stock = oneTime.computeIfAbsent(device, k -> new ArrayDeque<>());
            final JsonArray keys = in.getAsJsonArray("one_time");
            if (stock.size() + keys.size() > LumosConstants.ONE_TIME_PREKEYS_UPLOADED_MAX) {
                return code(400, "ERR_PREKEY_UPLOAD_INVALID");
            }
            for (final JsonElement k : keys) {
                if (!used.add(k.getAsJsonObject().get("key_id").getAsString())) {
                    return code(400, "ERR_PREKEY_UPLOAD_INVALID");
                }
            }
            for (final JsonElement k : keys) {
                stock.add(k.getAsJsonObject());
            }
        }
        return empty(204);
    }

    private Response claim(final String target) {
        if (!bundles.containsKey(target) || revoked.contains(target)) {
            return code(404, "NOT_FOUND");
        }
        final JsonObject o = new JsonObject();
        o.add("bundle", bundles.get(target));
        final JsonObject k = oneTime.getOrDefault(target, new ArrayDeque<>()).poll();
        if (k != null) {
            o.add("one_time", k);
        }
        return json(200, o);
    }

    private Response submit(final JsonObject in) {
        if (rateLimitRetryAfter >= 0) {
            final long after = rateLimitRetryAfter;
            rateLimitRetryAfter = -1;
            return new Response(429, "{\"code\":\"ERR_RELAY_RATE_LIMITED\"}", after);
        }
        final String recipient = in.get("recipient").getAsString();
        final String proof = Hex.encode(LumosCrypto.sha256(unhex(in.get("capability").getAsString())));
        if (!proofs.getOrDefault(recipient, new HashSet<>()).contains(proof)) {
            return empty(202);
        }
        final LinkedHashMap<String, JsonObject> box = mailboxes.computeIfAbsent(recipient, k -> new LinkedHashMap<>());
        final String id = in.get("message_id").getAsString();
        final JsonObject existing = box.get(id);
        if (existing != null) {
            return existing.get("payload").getAsString().equals(in.get("payload").getAsString())
                    ? empty(202) : code(400, "ERR_CRYPTO_REPLAY");
        }
        if (box.size() >= quotas.getOrDefault(recipient, Integer.MAX_VALUE)) {
            return code(400, "ERR_RELAY_MAILBOX_FULL");
        }
        final JsonObject e = new JsonObject();
        e.addProperty("message_id", id);
        e.addProperty("class", in.get("class").getAsInt());
        e.addProperty("sender_proof", proof);
        e.addProperty("created_at", now());
        e.addProperty("expires_at", now() + TTL_SECONDS);
        e.addProperty("payload", in.get("payload").getAsString());
        box.put(id, e);
        stored++;
        return empty(202);
    }

    private Response fetch(final String device, final int limit) {
        final JsonArray out = new JsonArray();
        for (final JsonObject e : mailboxes.getOrDefault(device, new LinkedHashMap<>()).values()) {
            if (out.size() == limit) {
                break;
            }
            if (serveExpired || e.get("expires_at").getAsLong() > now()) {
                out.add(e);
            }
        }
        final JsonObject o = new JsonObject();
        o.add("envelopes", out);
        return json(200, o);
    }

    private static byte[] unhex(final String s) {
        final byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static boolean same(final byte[] a, final byte[] b) {
        return Arrays.equals(a, b);
    }
}

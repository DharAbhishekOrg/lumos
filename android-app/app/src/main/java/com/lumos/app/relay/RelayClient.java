/*
 * Lumos - android-app/app / The registry and relay contract as typed calls.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, TRS-BCK-003, TRS-BCK-005, TRS-BCK-007, SRS-RMT-002, SRS-RMT-003, SRS-RMT-015, TRS-TRN-012
 * ADRs: ADR-001
 * Threading: any
 * Notes: One method per backend route, in the shapes backend/internal/api
 * serves: lowercase hex for keys and ids, standard base64 for payloads. The
 * client sends only what the route needs and reads only what it documents;
 * a response that does not parse is BUSY (the server is misbehaving, retry
 * later), never a crash (SRS-RMT-009). Statuses map to RelayException kinds
 * in one place, so every caller retries the same way. A fetch asks for at
 * most FETCH_BATCH envelopes, sized so the largest possible batch fits the
 * transport's response cap. The relay never sees a plaintext: what goes up
 * is the already-sealed envelope (SRS-RMT-002).
 */

package com.lumos.app.relay;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.x3dh.X3dh;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Typed calls to the registry and relay. */
public final class RelayClient {

    /** Envelopes asked for per fetch. */
    static final int FETCH_BATCH = 10;
    /** Bytes of a routing id. */
    public static final int ROUTING_ID_SIZE = 16;
    /** Bytes of a message id. */
    public static final int MESSAGE_ID_SIZE = 16;
    /** Bytes of a key, a capability, a proof, a challenge and a credential. */
    public static final int KEY_SIZE = 32;
    private static final int SIGNATURE_SIZE = 64;

    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int ACCEPTED = 202;
    private static final int NO_CONTENT = 204;
    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;
    private static final int TOO_MANY = 429;
    private static final int FIRST_SERVER_ERROR = 500;

    private final HttpTransport http;
    private final String bearer;

    private RelayClient(final HttpTransport http, final String bearer) {
        this.http = http;
        this.bearer = bearer;
    }

    /**
     * A client with no credential: it can only fetch a challenge and register.
     *
     * @param http the transport
     * @return the client
     */
    public static RelayClient anonymous(final HttpTransport http) {
        return new RelayClient(http, null);
    }

    /**
     * The same transport, acting as a registered device.
     *
     * @param credential the credential the registry issued
     * @return the authenticated client
     */
    public RelayClient as(final byte[] credential) {
        return new RelayClient(http, Hex.encode(credential));
    }

    // ---- registration -----------------------------------------------------------------------

    /**
     * A fresh registration challenge.
     *
     * @return the 32-byte challenge
     * @throws RelayException when it could not be had
     */
    public byte[] challenge() throws RelayException {
        final Json.Obj o = expectBody(call("POST", "/v1/auth/challenge", null), OK);
        return read(() -> Hex.decode(o.string("challenge"), KEY_SIZE));
    }

    /**
     * Registers a device key against a challenge it signed.
     *
     * @param challenge the challenge
     * @param devicePub the device's Ed25519 public key
     * @param signature its signature over the auth message
     * @return the registration
     * @throws RelayException when refused or unreachable
     */
    public RelayTypes.Registration register(final byte[] challenge, final byte[] devicePub, final byte[] signature)
            throws RelayException {
        requireLength(challenge, KEY_SIZE);
        requireLength(devicePub, KEY_SIZE);
        requireLength(signature, SIGNATURE_SIZE);
        final String body = Json.object().put("challenge", Hex.encode(challenge))
                .put("device_key", Hex.encode(devicePub)).put("signature", Hex.encode(signature)).end();
        final Json.Obj o = expectBody(call("POST", "/v1/devices", body), CREATED);
        return read(() -> new RelayTypes.Registration(Hex.decode(o.string("routing_id"), ROUTING_ID_SIZE),
                Hex.decode(o.string("credential"), KEY_SIZE)));
    }

    /**
     * Revokes this device's registration and everything behind it.
     *
     * @throws RelayException when refused or unreachable
     */
    public void revokeSelf() throws RelayException {
        expectEmpty(call("DELETE", "/v1/devices/self", null), NO_CONTENT);
    }

    // ---- prekeys ----------------------------------------------------------------------------

    /**
     * Publishes a signed bundle (its one-time fields are ignored), one-time
     * prekeys, or both.
     *
     * @param bundle the signed half to publish, or null to only add one-time keys
     * @param oneTime the one-time keys to add, possibly none
     * @throws RelayException when refused or unreachable
     */
    public void publishPrekeys(final X3dh.Bundle bundle, final List<RelayTypes.OneTimeKey> oneTime)
            throws RelayException {
        final Json.Writer w = Json.object();
        if (bundle != null) {
            w.putRaw("bundle", bundleJson(bundle));
        }
        if (!oneTime.isEmpty()) {
            final List<String> keys = new ArrayList<>();
            for (final RelayTypes.OneTimeKey k : oneTime) {
                requireLength(k.publicKey(), KEY_SIZE);
                keys.add(Json.object().put("key_id", k.id()).put("public_key", Hex.encode(k.publicKey())).end());
            }
            w.putRaw("one_time", Json.array(keys));
        }
        expectEmpty(call("PUT", "/v1/prekeys", w.end()), NO_CONTENT);
    }

    private static String bundleJson(final X3dh.Bundle b) {
        return Json.object().put("identity_key", Hex.encode(b.identityEd()))
                .put("identity_dh", Hex.encode(b.identityDh()))
                .put("identity_dh_sig", Hex.encode(b.identityDhSig()))
                .put("spk_id", b.spkId()).put("spk", Hex.encode(b.spk())).put("spk_sig", Hex.encode(b.spkSig()))
                .end();
    }

    /**
     * This device's one-time prekey stock.
     *
     * @return the stock
     * @throws RelayException when refused or unreachable
     */
    public RelayTypes.PrekeyStatus prekeyStatus() throws RelayException {
        final Json.Obj o = expectBody(call("GET", "/v1/prekeys/status", null), OK);
        return read(() -> new RelayTypes.PrekeyStatus(o.integer("one_time_remaining"), o.bool("needs_replenish")));
    }

    /**
     * Claims a peer's bundle and, when one is left, one of its one-time
     * prekeys - never the same one twice (TRS-CRY-005). The bundle is not
     * verified here; ChatStore.startSession verifies it against the pinned
     * identity.
     *
     * @param routingId the peer's routing id
     * @return the bundle, or empty when the peer is unknown, revoked or has none
     * @throws RelayException when refused or unreachable
     */
    public Optional<X3dh.Bundle> claim(final byte[] routingId) throws RelayException {
        requireLength(routingId, ROUTING_ID_SIZE);
        final HttpTransport.Response r = call("POST", "/v1/prekeys/" + Hex.encode(routingId) + "/claim", null);
        if (r.status() == NOT_FOUND) {
            return Optional.empty();
        }
        final Json.Obj o = expectBody(r, OK);
        return Optional.of(read(() -> {
            final Json.Obj b = o.object("bundle");
            final Json.Obj k = o.optObject("one_time");
            return new X3dh.Bundle(Hex.decode(b.string("identity_key"), KEY_SIZE),
                    Hex.decode(b.string("identity_dh"), KEY_SIZE),
                    Hex.decode(b.string("identity_dh_sig"), SIGNATURE_SIZE), uint32(b.integer("spk_id")),
                    Hex.decode(b.string("spk"), KEY_SIZE), Hex.decode(b.string("spk_sig"), SIGNATURE_SIZE),
                    k == null ? 0 : uint32(k.integer("key_id")),
                    k == null ? null : Hex.decode(k.string("public_key"), KEY_SIZE));
        }));
    }

    // ---- mailbox ----------------------------------------------------------------------------

    /**
     * Lets whoever holds the capability behind this proof send to this device.
     *
     * @param proof SHA-256 of the capability
     * @throws RelayException when refused or unreachable
     */
    public void allow(final byte[] proof) throws RelayException {
        requireLength(proof, KEY_SIZE);
        expectEmpty(call("PUT", "/v1/mailbox/capabilities", Json.object().put("proof", Hex.encode(proof)).end()),
                NO_CONTENT);
    }

    /**
     * Stops a capability from sending to this device (block, unmatch).
     *
     * @param proof SHA-256 of the capability
     * @throws RelayException when refused or unreachable
     */
    public void revoke(final byte[] proof) throws RelayException {
        requireLength(proof, KEY_SIZE);
        expectEmpty(call("DELETE", "/v1/mailbox/capabilities/" + Hex.encode(proof), null), NO_CONTENT);
    }

    /**
     * Uploads one envelope. A stored send, a retry of one already stored and
     * a send on a revoked capability are the same success, so a blocked
     * sender learns nothing (SRS-MAT-003).
     *
     * @param s the send
     * @throws RelayException when it was not taken
     */
    public void submit(final RelayTypes.Send s) throws RelayException {
        requireLength(s.recipient(), ROUTING_ID_SIZE);
        requireLength(s.messageId(), MESSAGE_ID_SIZE);
        requireLength(s.capability(), KEY_SIZE);
        final String body = Json.object().put("recipient", Hex.encode(s.recipient()))
                .put("message_id", Hex.encode(s.messageId())).put("class", s.messageClass())
                .put("capability", Hex.encode(s.capability()))
                .put("payload", Base64.getEncoder().encodeToString(s.payload())).end();
        expectEmpty(call("POST", "/v1/envelopes", body), ACCEPTED);
    }

    /**
     * Fetches up to FETCH_BATCH waiting envelopes. They stay on the relay
     * until acknowledged.
     *
     * @return the envelopes, oldest first
     * @throws RelayException when refused or unreachable
     */
    public List<RelayTypes.Envelope> fetch() throws RelayException {
        final Json.Obj o = expectBody(call("GET", "/v1/envelopes?limit=" + FETCH_BATCH, null), OK);
        return read(() -> {
            final List<RelayTypes.Envelope> out = new ArrayList<>();
            for (final Json.Obj e : o.objects("envelopes")) {
                final long cls = e.integer("class");
                if (cls < 0 || cls > 0xFF) {
                    throw new Json.MalformedException("class out of range");
                }
                out.add(new RelayTypes.Envelope(Hex.decode(e.string("message_id"), MESSAGE_ID_SIZE), (int) cls,
                        Hex.decode(e.string("sender_proof"), KEY_SIZE), e.integer("created_at"),
                        e.integer("expires_at"), base64(e.string("payload"))));
            }
            if (out.size() > FETCH_BATCH) {
                throw new Json.MalformedException("more envelopes than asked for");
            }
            return out;
        });
    }

    /**
     * Acknowledges envelopes, deleting them from the relay (SRS-RMT-015).
     *
     * @param messageIds the ids
     * @return how many the relay deleted
     * @throws RelayException when refused or unreachable
     */
    public long ack(final List<byte[]> messageIds) throws RelayException {
        final List<String> ids = new ArrayList<>();
        for (final byte[] id : messageIds) {
            requireLength(id, MESSAGE_ID_SIZE);
            ids.add(Hex.encode(id));
        }
        final Json.Obj o = expectBody(call("POST", "/v1/envelopes/ack",
                Json.object().putRaw("message_ids", Json.stringArray(ids)).end()), OK);
        return read(() -> o.integer("acknowledged"));
    }

    // ---- plumbing ---------------------------------------------------------------------------

    /** The largest body a full fetch can produce: the transport cap must hold it. */
    static long largestFetchBody() {
        final long payload = 4L * ((LumosConstants.RELAY_TEXT_ENVELOPE_MAX_BYTES_MAX + 2) / 3);
        final long perEnvelope = payload + 2L * MESSAGE_ID_SIZE + 2L * KEY_SIZE + 160;
        return FETCH_BATCH * perEnvelope + 32;
    }

    private HttpTransport.Response call(final String method, final String path, final String body)
            throws RelayException {
        try {
            return http.exchange(method, path, bearer, body);
        } catch (final IOException e) {
            throw RelayException.offline();
        }
    }

    private static Json.Obj expectBody(final HttpTransport.Response r, final int want) throws RelayException {
        failUnless(r, want);
        try {
            return Json.parseObject(r.body());
        } catch (final Json.MalformedException e) {
            throw RelayException.malformedResponse();
        }
    }

    private static void expectEmpty(final HttpTransport.Response r, final int want) throws RelayException {
        failUnless(r, want);
    }

    private static void failUnless(final HttpTransport.Response r, final int want) throws RelayException {
        if (r.status() == want) {
            return;
        }
        final String code = codeOf(r.body());
        final int s = r.status();
        final RelayException.Kind kind;
        if (s == UNAUTHORIZED) {
            kind = RelayException.Kind.AUTH;
        } else if (s == TOO_MANY || s >= FIRST_SERVER_ERROR || LumosErrors.ERR_RELAY_MAILBOX_FULL.equals(code)) {
            kind = RelayException.Kind.BUSY;
        } else if (s == NOT_FOUND) {
            kind = RelayException.Kind.NOT_FOUND;
        } else {
            kind = RelayException.Kind.REFUSED;
        }
        throw new RelayException(kind, code, r.retryAfterSeconds());
    }

    private static String codeOf(final String body) {
        try {
            final Json.Obj o = Json.parseObject(body);
            final String c = o.string("code");
            return c.matches("[A-Z_]{1,64}") ? c : "";
        } catch (final Json.MalformedException e) {
            return "";
        }
    }

    /** A parse step over a response body. */
    private interface Parse<T> {
        T run() throws Json.MalformedException;
    }

    private static <T> T read(final Parse<T> p) throws RelayException {
        try {
            return p.run();
        } catch (final Json.MalformedException | IllegalArgumentException e) {
            throw RelayException.malformedResponse();
        }
    }

    private static byte[] base64(final String s) throws Json.MalformedException {
        try {
            return Base64.getDecoder().decode(s);
        } catch (final IllegalArgumentException e) {
            throw new Json.MalformedException("not base64");
        }
    }

    private static long uint32(final long v) throws Json.MalformedException {
        if (v < 0 || v > 0xFFFF_FFFFL) {
            throw new Json.MalformedException("not a uint32");
        }
        return v;
    }

    private static void requireLength(final byte[] b, final int n) {
        if (b.length != n) {
            throw new IllegalArgumentException("wrong length");
        }
    }
}

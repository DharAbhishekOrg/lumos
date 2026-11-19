/*
 * Lumos - protocol-conformance / Java conformance to the HELLO exchange vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, TRS-PROTO-015, TRS-ENV-002, SRS-IDN-002, TRS-TST-001
 * ADRs: ADR-002, ADR-008
 * Threading: any
 * Notes: The Java quarter of the hello_exchange family. The dual exchange
 * replays byte for byte through the shared Hello/HelloExchange classes - the
 * code the Android presence layer actually calls - with the JCA as the signer
 * seam: Ed25519 signing is deterministic, so the JVM's signatures must equal
 * the committed ones that libsodium will reproduce on a device. Every
 * negative fails with its exact stable code through the mode the file names,
 * including the bare replay, which must fail BEFORE any cryptography runs.
 */
package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Holds the shared HELLO code to the committed golden vectors. */
public class HelloConformanceTest {

    private static final String FILE = "test-vectors/session/hello_exchange.json";

    /** A party rebuilt from its committed seeds, with a JCA signer seam. */
    private static final class Party {

        private final HelloExchange exchange;
        private final byte[] edPublic;

        Party(final JsonObject p) throws java.security.GeneralSecurityException {
            final byte[] seed = Vectors.hex(p, "testonly_ed_seed_hex");
            final PrivateKey edPriv = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            final Hello.Signer signer = message -> {
                final Signature s = Signature.getInstance("Ed25519");
                s.initSign(edPriv);
                s.update(message);
                return s.sign();
            };
            this.edPublic = Vectors.hex(p, "ed_public_hex");
            final Hello.Fields fields = new Hello.Fields(
                    LumosConstants.PROTOCOL_VERSION_ENCODED,
                    Vectors.i(p, "capability_bits"),
                    Vectors.l(p, "venue_hint"),
                    Vectors.hex(p, "eid_hex"),
                    edPublic,
                    Vectors.hex(p, "x_public_hex"),
                    Vectors.hex(p, "binding_sig_hex"),
                    new byte[Hello.NONCE_LEN]);
            this.exchange = new HelloExchange(signer, fields);
        }
    }

    @Test
    void theDualExchangeReplaysByteForByte() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        final Party initiator = new Party(root.getAsJsonObject("initiator"));
        final Party responder = new Party(root.getAsJsonObject("responder"));
        final JsonObject ex = root.getAsJsonObject("exchange");

        final byte[] iHello = initiator.exchange.start(Vectors.hex(ex, "initiator_nonce_hex"));
        assertEquals(Vectors.str(ex, "initiator_hello_hex"), Vectors.toHex(iHello),
                "initiator hello must match the committed bytes");

        final byte[] rAck = responder.exchange.onHello(iHello);
        assertEquals(Vectors.str(ex, "responder_ack_hex"), Vectors.toHex(rAck),
                "responder ack must match - the JCA must sign the same bytes libsodium will");

        final HelloExchange.Peer peerOfInitiator = initiator.exchange.onAck(rAck);
        assertEquals(Vectors.str(root.getAsJsonObject("responder"), "peer_id_hex"),
                Vectors.toHex(peerOfInitiator.id()), "responder peer id");
        assertArrayEquals(responder.edPublic, peerOfInitiator.fields().ed25519Public());

        final byte[] rHello = responder.exchange.start(Vectors.hex(ex, "responder_nonce_hex"));
        assertEquals(Vectors.str(ex, "responder_hello_hex"), Vectors.toHex(rHello));
        final byte[] iAck = initiator.exchange.onHello(rHello);
        assertEquals(Vectors.str(ex, "initiator_ack_hex"), Vectors.toHex(iAck));
        final HelloExchange.Peer peerOfResponder = responder.exchange.onAck(iAck);
        assertEquals(Vectors.str(root.getAsJsonObject("initiator"), "peer_id_hex"),
                Vectors.toHex(peerOfResponder.id()), "initiator peer id");

        // The hintless premise of the family (TRS-PROTO-018): the responder's
        // authenticated EID is zeros, and it became a peer regardless.
        assertArrayEquals(new byte[Hello.EID_LEN], peerOfInitiator.fields().eid(),
                "the responder must be the hintless shape");
    }

    @Test
    void everyNegativeFailsWithItsStableCode() {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "negatives").isEmpty(),
                "a family without negatives proves nothing");
        for (final JsonElement e : Vectors.arr(root, "negatives")) {
            final JsonObject c = Vectors.obj(e);
            final String name = Vectors.str(c, "name");
            final byte[] payload = Vectors.hex(c, "payload_hex");
            try {
                switch (Vectors.str(c, "mode")) {
                    case "parse_hello":
                        Hello.parseHello(payload);
                        break;
                    case "verify_ack":
                        Hello.verifyAck(Vectors.hex(c, "own_hello_hex"), payload);
                        break;
                    case "ack_without_outstanding":
                        new HelloExchange(message -> new byte[64], emptyFields())
                                .onAck(payload);
                        break;
                    default:
                        fail(name + ": unknown mode");
                }
                fail(name + ": a payload that must be rejected authenticated");
            } catch (final Frame.WireException ex) {
                assertEquals(Vectors.str(c, "expected_error"), ex.code(), name + ": stable code");
            }
        }
    }

    @Test
    void thePeerIdDerivationIsIdentityMaterialAlone() {
        final JsonObject root = Vectors.load(FILE);
        final JsonObject initiator = root.getAsJsonObject("initiator");
        final byte[] edPublic = Vectors.hex(initiator, "ed_public_hex");
        final byte[] id = Hello.peerId(edPublic);
        assertEquals(Vectors.str(initiator, "peer_id_hex"), Vectors.toHex(id));
        assertEquals(Hello.PEER_ID_LEN, id.length);
        // SRS-IDN-002: the derivation input is info || Ed25519 public, nothing
        // else - recomputed here from primitives as the independent check.
        final byte[] recomputed = Arrays.copyOf(LumosCrypto.sha256(LumosCrypto.concat(
                LumosConstants.PEER_ID_KDF_INFO_VALUE.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8), edPublic)),
                Hello.PEER_ID_LEN);
        assertArrayEquals(recomputed, id);
    }

    private static Hello.Fields emptyFields() {
        return new Hello.Fields(LumosConstants.PROTOCOL_VERSION_ENCODED, 0, 0L,
                new byte[Hello.EID_LEN], new byte[32], new byte[32], new byte[64],
                new byte[Hello.NONCE_LEN]);
    }
}

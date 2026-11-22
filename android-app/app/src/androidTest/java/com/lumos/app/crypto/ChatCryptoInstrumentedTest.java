/*
 * Lumos - android-app/app / The chat and HELLO crypto on a device below API 33, through libsodium.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-002, TRS-CRY-003, TRS-CRY-006, SRS-RMT-007, SRS-RMT-008, TRS-TST-001
 * ADRs: ADR-001, ADR-008
 * Threading: test-only
 * Notes: WP-05 S7d. CI's emulator runs API 30, where the JCA has neither
 * XDH, the XEC/EdEC key specs nor Ed25519 - so before this slice every test
 * here would have failed on the first agreement. The protocol code under
 * test is the same protocol-java the JVM conformance suite runs; only the
 * backend differs, and it is the one the app installed itself (the test
 * process starts LumosApplication like any launch). Each family is replayed
 * byte for byte: AEAD known answers and tampers, identity bindings, X3DH
 * from both sides, every ratchet sequence and its max-skip negative, the
 * whole scripted remote-text session, and the dual HELLO exchange with its
 * negatives - signed here by libsodium, so the committed signatures also
 * prove the app's signing path.
 */

package com.lumos.app.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.wire.LumosCrypto;
import com.lumos.protocol.x3dh.X3dh;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.KeyAgreement;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The protocol crypto, on device, through the backend the app installed. */
@RunWith(AndroidJUnit4.class)
public class ChatCryptoInstrumentedTest {

    private static final int FIRST_API_WITH_XDH = 33;
    private static final byte[] ZERO_SALT = new byte[32];

    @Test
    public void theAppInstalledTheLibsodiumBackend() {
        assertTrue("LumosApplication must install libsodium before any protocol code runs, found "
                + LumosCrypto.backend().getClass().getName(), LumosCrypto.backend() instanceof LibsodiumCryptoBackend);
    }

    @Test
    public void belowApi33TheJcaPathReallyIsAbsent() {
        if (Build.VERSION.SDK_INT >= FIRST_API_WITH_XDH) {
            return; // the gap this slice closes only exists below 33
        }
        try {
            KeyAgreement.getInstance("XDH");
            fail("XDH resolved below API 33; the premise of WP-05 S7d changed");
        } catch (final NoSuchAlgorithmException expected) {
            // Exactly why the chat path needs libsodium here.
        }
        try {
            Class.forName("java.security.spec.XECPublicKeySpec");
            fail("XECPublicKeySpec exists below API 33");
        } catch (final ClassNotFoundException expected) {
            // Ditto.
        }
    }

    @Test
    public void aLowOrderPeerKeyIsRefusedNotAgreed() throws Exception {
        try {
            LumosCrypto.x25519(new byte[32], new byte[32]);
            fail("an all-zero agreement must throw");
        } catch (final GeneralSecurityException expected) {
            // The CryptoBackend contract, held by both backends.
        }
    }

    @Test
    public void aeadKnownAnswersAndTampers() throws Exception {
        final JSONObject root = ChatVectors.load("crypto/aead_kat.json");
        final JSONArray positive = root.getJSONArray("positive");
        assertTrue(positive.length() > 0);
        for (int i = 0; i < positive.length(); i++) {
            final JSONObject c = positive.getJSONObject(i);
            final byte[] key = ChatVectors.hex(c, "derived_key_hex");
            final byte[] nonce = ChatVectors.hex(c, "nonce_hex");
            final byte[] ct = LumosCrypto.aeadSeal(key, nonce, ChatVectors.hex(c, "plaintext_hex"),
                    ChatVectors.hex(c, "aad_hex"));
            assertEquals(c.getString("ciphertext_hex"), ChatVectors.toHex(ct));
            assertArrayEquals(ChatVectors.hex(c, "plaintext_hex"),
                    LumosCrypto.aeadOpen(key, nonce, ct, ChatVectors.hex(c, "aad_hex")));
        }
        final JSONArray tamper = root.getJSONArray("tamper");
        assertTrue(tamper.length() > 0);
        for (int i = 0; i < tamper.length(); i++) {
            final JSONObject c = tamper.getJSONObject(i);
            final byte[] okm = LumosCrypto.hkdf(ChatVectors.hex(c, "message_key_hex"), ZERO_SALT,
                    LumosConstants.RATCHET_MESSAGE_KDF_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                    LumosCrypto.AEAD_KEY_SIZE + LumosCrypto.AEAD_NONCE_SIZE);
            try {
                LumosCrypto.aeadOpen(LumosCrypto.prefix(okm, LumosCrypto.AEAD_KEY_SIZE), ChatVectors.hex(c, "nonce_hex"),
                        ChatVectors.hex(c, "ciphertext_hex"), ChatVectors.hex(c, "aad_hex"));
                fail("tamper case " + c.getString("name") + " authenticated");
            } catch (final GeneralSecurityException expected) {
                assertEquals("ERR_CRYPTO_AEAD_AUTH_FAILED", c.getString("expected_error"));
            }
        }
    }

    @Test
    public void identityBindingsVerifyAndForgeriesDoNot() throws Exception {
        final JSONObject root = ChatVectors.load("crypto/identity_binding.json");
        final byte[] info = root.getString("binding_info").getBytes(StandardCharsets.UTF_8);
        final JSONArray positive = root.getJSONArray("positive");
        for (int i = 0; i < positive.length(); i++) {
            final JSONObject c = positive.getJSONObject(i);
            assertEquals(c.getString("x_public_hex"),
                    ChatVectors.toHex(LumosCrypto.x25519PublicFromPrivate(ChatVectors.hex(c, "testonly_x_scalar_hex"))));
            assertTrue(c.getString("name"), LumosCrypto.ed25519Verify(ChatVectors.hex(c, "ed_public_hex"),
                    LumosCrypto.concat(info, ChatVectors.hex(c, "x_public_hex")),
                    ChatVectors.hex(c, "binding_signature_hex")));
        }
        final JSONArray negative = root.getJSONArray("negative");
        assertTrue(negative.length() > 0);
        for (int i = 0; i < negative.length(); i++) {
            final JSONObject c = negative.getJSONObject(i);
            assertFalse(c.getString("name"), LumosCrypto.ed25519Verify(ChatVectors.hex(c, "ed_public_hex"),
                    LumosCrypto.concat(info, ChatVectors.hex(c, "x_public_hex")),
                    ChatVectors.hex(c, "binding_signature_hex")));
        }
    }

    @Test
    public void x3dhAgreesFromBothSidesAndRefusesBadBundles() throws Exception {
        final JSONObject root = ChatVectors.load("crypto/x3dh_kat.json");
        final byte[] matchId = ChatVectors.hex(root, "match_id_hex");
        final JSONArray cases = root.getJSONArray("cases");
        assertTrue(cases.length() > 0);
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            final boolean withOpk = c.getBoolean("with_one_time_prekey");
            final X3dh.Bundle bundle = new X3dh.Bundle(ChatVectors.hex(c, "responder_identity_ed_pub_hex"),
                    ChatVectors.hex(c, "responder_identity_dh_pub_hex"), ChatVectors.hex(c, "identity_dh_sig_hex"),
                    c.getLong("signed_prekey_id"), ChatVectors.hex(c, "responder_signed_prekey_pub_hex"),
                    ChatVectors.hex(c, "signed_prekey_sig_hex"), withOpk ? c.getLong("one_time_prekey_id") : 0,
                    withOpk ? ChatVectors.hex(c, "responder_one_time_prekey_pub_hex") : null);
            final X3dh.Result init = X3dh.initiate(ChatVectors.hex(c, "initiator_identity_ed_pub_hex"),
                    ChatVectors.hex(c, "testonly_initiator_identity_dh_priv_hex"),
                    ChatVectors.hex(c, "testonly_initiator_ephemeral_priv_hex"), bundle, matchId);
            assertEquals(c.getString("name"), c.getString("shared_secret_hex"), ChatVectors.toHex(init.sk()));
            assertEquals(c.getString("transcript_ad_hex"), ChatVectors.toHex(init.ad()));
            final X3dh.Result resp = X3dh.respond(ChatVectors.hex(c, "responder_identity_ed_pub_hex"),
                    ChatVectors.hex(c, "testonly_responder_identity_dh_priv_hex"),
                    ChatVectors.hex(c, "testonly_responder_signed_prekey_priv_hex"), c.getLong("signed_prekey_id"),
                    withOpk ? ChatVectors.hex(c, "testonly_responder_one_time_prekey_priv_hex") : null,
                    withOpk ? c.getLong("one_time_prekey_id") : 0, ChatVectors.hex(c, "initiator_identity_ed_pub_hex"),
                    ChatVectors.hex(c, "initiator_identity_dh_pub_hex"), ChatVectors.hex(c, "initiator_ephemeral_pub_hex"),
                    matchId);
            assertArrayEquals("the responder agrees", init.sk(), resp.sk());
        }
        final JSONArray negatives = root.getJSONArray("negatives");
        for (int i = 0; i < negatives.length(); i++) {
            final JSONObject c = negatives.getJSONObject(i);
            try {
                X3dh.verifyBundle(new X3dh.Bundle(ChatVectors.hex(c, "responder_identity_ed_pub_hex"),
                        ChatVectors.hex(c, "responder_identity_dh_pub_hex"), ChatVectors.hex(c, "identity_dh_sig_hex"),
                        c.getLong("signed_prekey_id"), ChatVectors.hex(c, "responder_signed_prekey_pub_hex"),
                        ChatVectors.hex(c, "signed_prekey_sig_hex"), 0, null));
                fail("negative bundle " + c.getString("name") + " verified");
            } catch (final Frame.WireException e) {
                assertEquals(c.getString("expected_error"), e.code());
            }
        }
    }

    private static DoubleRatchet[] ratchetPair(final JSONObject root) throws Exception {
        final byte[] sk = ChatVectors.hex(root, "shared_secret_hex");
        final byte[] ad = ChatVectors.hex(root, "associated_data_hex");
        final byte[] respPriv = ChatVectors.hex(root, "testonly_responder_ratchet_priv_hex");
        return new DoubleRatchet[] {
            DoubleRatchet.initiator(sk, ad, LumosCrypto.x25519PublicFromPrivate(respPriv),
                    ChatVectors.hex(root, "testonly_initiator_ratchet_priv_hex"), DoubleRatchet.Bounds.defaults()),
            DoubleRatchet.responder(sk, ad, respPriv, DoubleRatchet.Bounds.defaults()),
        };
    }

    @Test
    public void everyRatchetSequenceDecryptsByteForByte() throws Exception {
        final JSONObject root = ChatVectors.load("crypto/ratchet_sequences.json");
        final long now = 1_700_000_000L;
        final JSONArray sequences = root.getJSONArray("sequences");
        assertTrue(sequences.length() > 0);
        for (int s = 0; s < sequences.length(); s++) {
            final JSONObject seq = sequences.getJSONObject(s);
            final DoubleRatchet[] p = ratchetPair(root);
            final DoubleRatchet.KeyProvider kpA = ChatVectors.recorded(root.getJSONArray("testonly_initiator_dh_turn_privs_hex"));
            final DoubleRatchet.KeyProvider kpB = ChatVectors.recorded(root.getJSONArray("testonly_responder_dh_turn_privs_hex"));
            final JSONArray messages = seq.getJSONArray("messages");
            for (int m = 0; m < messages.length(); m++) {
                final JSONObject msg = messages.getJSONObject(m);
                final boolean toBob = "a2b".equals(msg.getString("direction"));
                final byte[] pt = (toBob ? p[1] : p[0]).decrypt(DoubleRatchet.Header.parse(ChatVectors.hex(msg, "header_hex")),
                        ChatVectors.hex(msg, "ciphertext_hex"), now, toBob ? kpB : kpA);
                assertEquals(seq.getString("name"), msg.getString("plaintext_hex"), ChatVectors.toHex(pt));
            }
        }
        final JSONArray negatives = root.getJSONArray("negatives");
        for (int n = 0; n < negatives.length(); n++) {
            final JSONObject neg = negatives.getJSONObject(n);
            final DoubleRatchet[] p = ratchetPair(root);
            DoubleRatchet.Sealed last = null;
            for (int i = 0; i <= neg.getInt("gap"); i++) {
                last = p[0].encrypt(new byte[] {'x'}, now);
            }
            try {
                p[1].decrypt(last.header(), last.ciphertext(), now,
                        ChatVectors.recorded(root.getJSONArray("testonly_responder_dh_turn_privs_hex")));
                fail(neg.getString("name") + " decrypted");
            } catch (final Frame.WireException e) {
                assertEquals(neg.getString("expected_error"), e.code());
            }
        }
    }

    @Test
    public void theScriptedRemoteTextSessionReplaysByteForByte() throws Exception {
        final JSONObject s = ChatVectors.load("chat/remote_text.json").getJSONObject("session");
        final JSONObject a = s.getJSONObject("alice");
        final JSONObject b = s.getJSONObject("bob");
        final byte[] matchId = ChatVectors.hex(s, "match_id_hex");
        final long now = s.getLong("now");
        final byte[] aliceEd = ChatVectors.hex(a, "identity_ed_pub_hex");
        final RemoteSession.Identity alice = new RemoteSession.Identity(aliceEd,
                ChatVectors.hex(a, "testonly_identity_dh_priv_hex"), ChatVectors.hex(a, "identity_dh_sig_hex"));
        final RemoteSession.Identity bob = new RemoteSession.Identity(ChatVectors.hex(b, "identity_ed_pub_hex"),
                ChatVectors.hex(b, "testonly_identity_dh_priv_hex"), ChatVectors.hex(b, "identity_dh_sig_hex"));
        final X3dh.Bundle bundle = new X3dh.Bundle(ChatVectors.hex(b, "identity_ed_pub_hex"),
                ChatVectors.hex(b, "identity_dh_pub_hex"), ChatVectors.hex(b, "identity_dh_sig_hex"), b.getLong("spk_id"),
                ChatVectors.hex(b, "spk_pub_hex"), ChatVectors.hex(b, "spk_sig_hex"), b.getLong("opk_id"),
                ChatVectors.hex(b, "opk_pub_hex"));
        final DoubleRatchet.KeyProvider aliceKeys = ChatVectors.recorded(a.getJSONArray("testonly_ratchet_turn_privs_hex"));
        final DoubleRatchet.KeyProvider bobKeys = ChatVectors.recorded(b.getJSONArray("testonly_ratchet_turn_privs_hex"));
        final RemoteSession.Started started = RemoteSession.initiate(alice, bundle, matchId,
                ChatVectors.hex(a, "testonly_ephemeral_priv_hex"), ChatVectors.hex(a, "testonly_first_ratchet_priv_hex"));
        DoubleRatchet bobState = null;
        boolean heardBack = false;
        final JSONArray steps = s.getJSONArray("steps");
        final JSONArray messages = s.getJSONArray("messages");
        assertTrue(steps.length() > 0);
        for (int i = 0; i < steps.length(); i++) {
            final JSONObject step = steps.getJSONObject(i);
            final JSONObject m = messages.getJSONObject(step.getInt("message"));
            final boolean byBob = "bob".equals(step.getString("actor"));
            final byte[] envelope = ChatVectors.hex(m, "envelope_hex");
            final byte[] plaintext = ChatVectors.hex(m, "plaintext_hex");
            if ("seal".equals(step.getString("op"))) {
                final RemoteText.Plaintext p = RemoteText.decodePlaintext(plaintext);
                final byte[] sealed = byBob ? RemoteSession.seal(bobState, p, null, now)
                        : RemoteSession.seal(started.ratchet(), p, heardBack ? null : started.prekey(), now);
                assertEquals("seal must reproduce the Go envelope", ChatVectors.toHex(envelope), ChatVectors.toHex(sealed));
                continue;
            }
            final RemoteText.Envelope e = RemoteText.decodeEnvelope(envelope);
            final byte[] outerId = ChatVectors.hex(m, "message_id_hex");
            final RemoteText.Plaintext got;
            if (byBob) {
                if (bobState == null) {
                    assertNotNull(e.prekey());
                    bobState = RemoteSession.respond(bob, new RemoteSession.ResponderKeys(
                            ChatVectors.hex(b, "testonly_spk_priv_hex"), b.getLong("spk_id"),
                            ChatVectors.hex(b, "testonly_opk_priv_hex"), b.getLong("opk_id")), aliceEd, e.prekey(), matchId);
                }
                got = RemoteSession.open(bobState, e, outerId, now, bobKeys);
            } else {
                got = RemoteSession.open(started.ratchet(), e, outerId, now, aliceKeys);
                heardBack = true;
            }
            assertArrayEquals("open must yield the Go plaintext", plaintext, got.encode());
        }
    }

    /** A HELLO party rebuilt from its committed seed, signing through libsodium. */
    private static HelloExchange party(final JSONObject p, final LazySodiumAndroid sodium) throws Exception {
        final byte[] seed = ChatVectors.hex(p, "testonly_ed_seed_hex");
        final Hello.Signer signer = message -> {
            final byte[] pub = new byte[32];
            final byte[] secret = new byte[64];
            final byte[] sig = new byte[64];
            if (!sodium.cryptoSignSeedKeypair(pub, secret, seed)
                    || !sodium.cryptoSignDetached(sig, message, message.length, secret)) {
                throw new GeneralSecurityException("libsodium signing failed");
            }
            return sig;
        };
        return new HelloExchange(signer, new Hello.Fields(LumosConstants.PROTOCOL_VERSION_ENCODED,
                p.getInt("capability_bits"), p.getLong("venue_hint"), ChatVectors.hex(p, "eid_hex"),
                ChatVectors.hex(p, "ed_public_hex"), ChatVectors.hex(p, "x_public_hex"),
                ChatVectors.hex(p, "binding_sig_hex"), new byte[Hello.NONCE_LEN]));
    }

    @Test
    public void theHelloExchangeReplaysAndItsNegativesFail() throws Exception {
        final JSONObject root = ChatVectors.load("session/hello_exchange.json");
        final LazySodiumAndroid sodium = new LazySodiumAndroid(new SodiumAndroid());
        final HelloExchange initiator = party(root.getJSONObject("initiator"), sodium);
        final HelloExchange responder = party(root.getJSONObject("responder"), sodium);
        final JSONObject ex = root.getJSONObject("exchange");

        final byte[] iHello = initiator.start(ChatVectors.hex(ex, "initiator_nonce_hex"));
        assertEquals(ex.getString("initiator_hello_hex"), ChatVectors.toHex(iHello));
        final byte[] rAck = responder.onHello(iHello);
        assertEquals(ex.getString("responder_ack_hex"), ChatVectors.toHex(rAck));
        assertEquals(root.getJSONObject("responder").getString("peer_id_hex"),
                ChatVectors.toHex(initiator.onAck(rAck).id()));
        final byte[] rHello = responder.start(ChatVectors.hex(ex, "responder_nonce_hex"));
        assertEquals(ex.getString("responder_hello_hex"), ChatVectors.toHex(rHello));
        final byte[] iAck = initiator.onHello(rHello);
        assertEquals(ex.getString("initiator_ack_hex"), ChatVectors.toHex(iAck));
        assertEquals(root.getJSONObject("initiator").getString("peer_id_hex"),
                ChatVectors.toHex(responder.onAck(iAck).id()));

        final JSONArray negatives = root.getJSONArray("negatives");
        assertTrue(negatives.length() > 0);
        for (int i = 0; i < negatives.length(); i++) {
            final JSONObject c = negatives.getJSONObject(i);
            final byte[] payload = ChatVectors.hex(c, "payload_hex");
            try {
                switch (c.getString("mode")) {
                    case "parse_hello":
                        Hello.parseHello(payload);
                        break;
                    case "verify_ack":
                        Hello.verifyAck(ChatVectors.hex(c, "own_hello_hex"), payload);
                        break;
                    case "ack_without_outstanding":
                        new HelloExchange(message -> new byte[64], new Hello.Fields(
                                LumosConstants.PROTOCOL_VERSION_ENCODED, 0, 0L, new byte[Hello.EID_LEN], new byte[32],
                                new byte[32], new byte[64], new byte[Hello.NONCE_LEN])).onAck(payload);
                        break;
                    default:
                        fail(c.getString("name") + ": unknown mode");
                }
                fail(c.getString("name") + ": a payload that must be rejected authenticated");
            } catch (final Frame.WireException e) {
                assertEquals(c.getString("name"), c.getString("expected_error"), e.code());
            }
        }
    }
}

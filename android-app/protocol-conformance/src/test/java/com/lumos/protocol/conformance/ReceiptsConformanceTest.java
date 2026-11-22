/*
 * Lumos - android-app/protocol-conformance / Holds the Java relay route and receipts to the Go vectors.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-009, SRS-RMT-001, TRS-PROTO-007, TRS-TST-001
 * ADRs: ADR-001, ADR-002
 * Threading: test-only
 * Notes: chat/receipts.json (CR-005). The codecs decode and re-encode every
 * committed case byte for byte and refuse every negative with its code; the
 * recorded session replays - each seal reproducing the Go envelope, each
 * open the Go plaintext - and every relabel, tried on fresh state replayed
 * up to its open, fails with its code.
 */

package com.lumos.protocol.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.chat.Receipt;
import com.lumos.protocol.chat.RelayRoute;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import com.lumos.protocol.x3dh.X3dh;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** The Java relay route and receipts against the committed vectors. */
public class ReceiptsConformanceTest {

    private static final String FILE = "test-vectors/chat/receipts.json";

    @Test
    void everyRouteAndReceiptDecodesAndReencodesByteForByte() throws Exception {
        final JsonObject root = Vectors.load(FILE);
        assertFalse(Vectors.arr(root, "routes").isEmpty(), "an empty section proves nothing");
        for (final JsonElement el : Vectors.arr(root, "routes")) {
            final JsonObject c = Vectors.obj(el);
            final RelayRoute r = RelayRoute.decode(Vectors.hex(c, "encoded_hex"));
            assertEquals(Vectors.str(c, "routing_id_hex"), Vectors.toHex(r.routingId()));
            assertEquals(Vectors.str(c, "capability_hex"), Vectors.toHex(r.capability()));
            assertEquals(Vectors.str(c, "encoded_hex"), Vectors.toHex(r.encode()));
        }
        assertFalse(Vectors.arr(root, "receipts").isEmpty());
        for (final JsonElement el : Vectors.arr(root, "receipts")) {
            final JsonObject c = Vectors.obj(el);
            final Receipt r = Receipt.decode(Vectors.hex(c, "encoded_hex"));
            assertEquals(Vectors.i(c, "kind"), r.kind().code());
            assertEquals(Vectors.arr(c, "acked_hex").size(), r.acked().size());
            assertEquals(Vectors.str(c, "receipt_id_hex"), Vectors.toHex(r.id()));
            assertEquals(Vectors.str(c, "encoded_hex"), Vectors.toHex(r.encode()));
        }
    }

    @Test
    void everyNegativeEarnsItsExactCode() {
        final JsonObject root = Vectors.load(FILE);
        for (final JsonElement el : Vectors.arr(root, "route_negatives")) {
            final JsonObject c = Vectors.obj(el);
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> RelayRoute.decode(Vectors.hex(c, "encoded_hex")), Vectors.str(c, "name"));
            assertEquals(Vectors.str(c, "expected_error"), e.code(), Vectors.str(c, "name"));
        }
        for (final JsonElement el : Vectors.arr(root, "receipt_negatives")) {
            final JsonObject c = Vectors.obj(el);
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> Receipt.decode(Vectors.hex(c, "encoded_hex")), Vectors.str(c, "name"));
            assertEquals(Vectors.str(c, "expected_error"), e.code(), Vectors.str(c, "name"));
        }
    }

    private static DoubleRatchet.KeyProvider recorded(final JsonObject party) {
        final Deque<byte[]> keys = new ArrayDeque<>();
        for (final JsonElement e : Vectors.arr(party, "testonly_ratchet_turn_privs_hex")) {
            keys.add(Vectors.hexToBytes(e.getAsString()));
        }
        return () -> {
            if (keys.isEmpty()) {
                throw new GeneralSecurityException("recorded ratchet keys exhausted");
            }
            return keys.poll();
        };
    }

    /** The recorded session on fresh state. */
    private static final class Replay {
        private final JsonObject s;
        private final JsonObject a;
        private final JsonObject b;
        private final byte[] matchId;
        private final long now;
        private final RemoteSession.Started started;
        private final DoubleRatchet.KeyProvider aliceKeys;
        private final DoubleRatchet.KeyProvider bobKeys;
        private DoubleRatchet bobState;
        private boolean heardBack;

        Replay(final JsonObject s) throws Frame.WireException {
            this.s = s;
            a = Vectors.obj(s.get("alice"));
            b = Vectors.obj(s.get("bob"));
            matchId = Vectors.hex(s, "match_id_hex");
            now = Vectors.l(s, "now");
            final RemoteSession.Identity alice = new RemoteSession.Identity(Vectors.hex(a, "identity_ed_pub_hex"),
                    Vectors.hex(a, "testonly_identity_dh_priv_hex"), Vectors.hex(a, "identity_dh_sig_hex"));
            final X3dh.Bundle bundle = new X3dh.Bundle(Vectors.hex(b, "identity_ed_pub_hex"),
                    Vectors.hex(b, "identity_dh_pub_hex"), Vectors.hex(b, "identity_dh_sig_hex"),
                    Vectors.l(b, "spk_id"), Vectors.hex(b, "spk_pub_hex"), Vectors.hex(b, "spk_sig_hex"),
                    Vectors.l(b, "opk_id"), Vectors.hex(b, "opk_pub_hex"));
            started = RemoteSession.initiate(alice, bundle, matchId, Vectors.hex(a, "testonly_ephemeral_priv_hex"),
                    Vectors.hex(a, "testonly_first_ratchet_priv_hex"));
            aliceKeys = recorded(a);
            bobKeys = recorded(b);
        }

        JsonObject step(final int i) {
            return Vectors.obj(Vectors.arr(s, "steps").get(i));
        }

        JsonObject message(final int m) {
            return Vectors.obj(Vectors.arr(s, "messages").get(m));
        }

        void seal(final int i) throws Frame.WireException {
            final JsonObject m = message(Vectors.i(step(i), "message"));
            final byte[] pt = Vectors.hex(m, "plaintext_hex");
            final byte[] env;
            if (Vectors.i(m, "class") == MessageClass.TEXT_MESSAGE.code()) {
                env = RemoteSession.seal(started.ratchet(), RemoteText.decodePlaintext(pt),
                        heardBack ? null : started.prekey(), now);
            } else {
                env = Receipt.decode(pt).seal(bobState, null, now);
            }
            assertEquals(Vectors.str(m, "envelope_hex"), Vectors.toHex(env), "seal must reproduce the Go envelope");
        }

        byte[] open(final int i, final MessageClass cls, final byte[] id) throws Frame.WireException {
            final JsonObject st = step(i);
            final RemoteText.Envelope e = RemoteText.decodeEnvelope(
                    Vectors.hex(message(Vectors.i(st, "message")), "envelope_hex"));
            if ("bob".equals(Vectors.str(st, "actor"))) {
                if (bobState == null) {
                    final RemoteSession.Identity bob = new RemoteSession.Identity(Vectors.hex(b, "identity_ed_pub_hex"),
                            Vectors.hex(b, "testonly_identity_dh_priv_hex"), Vectors.hex(b, "identity_dh_sig_hex"));
                    bobState = RemoteSession.respond(bob, new RemoteSession.ResponderKeys(
                            Vectors.hex(b, "testonly_spk_priv_hex"), Vectors.l(b, "spk_id"),
                            Vectors.hex(b, "testonly_opk_priv_hex"), Vectors.l(b, "opk_id")),
                            Vectors.hex(a, "identity_ed_pub_hex"), e.prekey(), matchId);
                }
                return RemoteSession.open(bobState, e, id, now, bobKeys).encode();
            }
            heardBack = true;
            if (cls == MessageClass.TEXT_MESSAGE) {
                return RemoteSession.open(started.ratchet(), e, id, now, aliceKeys).encode();
            }
            return Receipt.open(started.ratchet(), e, id, cls, now, aliceKeys).encode();
        }

        void run(final int stop) throws Frame.WireException {
            for (int i = 0; i < stop; i++) {
                if ("seal".equals(Vectors.str(step(i), "op"))) {
                    seal(i);
                    continue;
                }
                final JsonObject m = message(Vectors.i(step(i), "message"));
                assertArrayEquals(Vectors.hex(m, "plaintext_hex"), open(i,
                        MessageClass.fromCode(Vectors.i(m, "class")), Vectors.hex(m, "message_id_hex")),
                        "open must yield the Go plaintext");
            }
        }
    }

    @Test
    void theRecordedSessionReplaysAndEveryRelabelFails() throws Exception {
        final JsonObject s = Vectors.obj(Vectors.load(FILE).get("session"));
        final int steps = Vectors.arr(s, "steps").size();
        new Replay(s).run(steps);
        assertFalse(Vectors.arr(s, "relabels").isEmpty());
        for (final JsonElement el : Vectors.arr(s, "relabels")) {
            final JsonObject rl = Vectors.obj(el);
            final Replay r = new Replay(s);
            int stop = -1;
            for (int i = 0; i < steps; i++) {
                if ("open".equals(Vectors.str(r.step(i), "op")) && Vectors.i(r.step(i), "message") == Vectors.i(rl, "message")) {
                    stop = i;
                    break;
                }
            }
            r.run(stop);
            final int at = stop;
            final Frame.WireException e = assertThrows(Frame.WireException.class,
                    () -> r.open(at, MessageClass.fromCode(Vectors.i(rl, "delivered_class")),
                            Vectors.hex(rl, "delivered_id_hex")), Vectors.str(rl, "name"));
            assertEquals(Vectors.str(rl, "expected_error"), e.code(), Vectors.str(rl, "name"));
        }
    }
}

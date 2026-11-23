/*
 * Lumos - android-app/app / Android's relay client against the composed staging backend, over real HTTP.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-006, SRS-RMT-001, SRS-RMT-003, SRS-RMT-004, SRS-RMT-005, SRS-RMT-016, SRS-RMT-017, TRS-CRY-005
 * ADRs: ADR-001, ADR-012
 * Threading: test-only
 * Notes: WP-05 S8, the cross-stack run S7c deferred here. Two devices are the
 * app's real chat and relay stack (ChatStore, RelaySync, PrekeyPublisher,
 * RouteExchange, RelaySessions on real SQLite files) talking through the
 * app's real UrlConnectionTransport to the lumosd image, PostgreSQL and Redis
 * from backend/deploy/staging/docker-compose.yml. Nothing is faked between them: the
 * registry verifies the device-auth and prekey signatures the Java code made,
 * the bundle a session starts from is the one the registry handed out, and
 * the envelopes are opened by the other device's ratchet. "Kill" drops every
 * object and reboots from the file, between steps. The clock is the real one,
 * because the backend's expiry runs on real time.
 *
 * Skipped without LUMOS_STAGING_URL; LUMOS_REQUIRE_STAGING=1 (the CI staging
 * job) turns the skip into a failure.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.lumos.app.Fixtures;
import com.lumos.app.store.RecordKind;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.match.MatchMachine;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StagingCrossStackTest {

    private static final SecureRandom RNG = new SecureRandom();

    private final List<AutoCloseable> open = new ArrayList<>();
    private Fixtures.TestClock clock;
    private HttpTransport transport;

    @BeforeEach
    void connect() throws Exception {
        final String base = System.getenv("LUMOS_STAGING_URL");
        if (base == null || base.isEmpty()) {
            if ("1".equals(System.getenv("LUMOS_REQUIRE_STAGING"))) {
                fail("LUMOS_REQUIRE_STAGING=1 but LUMOS_STAGING_URL is empty");
            }
            Assumptions.abort("no LUMOS_STAGING_URL");
        }
        clock = new Fixtures.TestClock(System.currentTimeMillis());
        transport = UrlConnectionTransport.to(new URL(base));
    }

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private static byte[] random(final int n) {
        final byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static RemoteText.TextEvent text(final String s) {
        return new RemoteText.TextEvent(TextEventKind.TEXT, null, s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> bodies(final RelayDevice d, final byte[] match) throws Exception {
        final List<String> out = new ArrayList<>();
        d.chat.messages(match).forEach(m -> out.add(new String(m.body(), StandardCharsets.UTF_8)));
        return out;
    }

    private RelayDevice device() throws Exception {
        final RelayDevice d = new RelayDevice(clock, transport, open);
        assertNull(d.sync.run().stoppedBy(), "registers with a signed challenge and publishes a signed bundle");
        return d;
    }

    private byte[] match(final RelayDevice initiator, final RelayDevice responder) throws Exception {
        final byte[] match = random(16);
        initiator.chat.acceptConversation(match, responder.edPub);
        responder.chat.acceptConversation(match, initiator.edPub);
        final MatchMachine.State matched = MatchMachine.State.MATCHED;
        assertEquals(RouteExchange.Taken.STORED, responder.exchange.take(match, matched,
                initiator.exchange.offer(match, matched).orElseThrow()));
        assertEquals(RouteExchange.Taken.STORED, initiator.exchange.take(match, matched,
                responder.exchange.offer(match, matched).orElseThrow()));
        assertNull(initiator.sync.run().stoppedBy(), "allows the peer's proof at the real relay");
        assertNull(responder.sync.run().stoppedBy());
        assertTrue(initiator.sessions.start(match, responder.edPub),
                "X3DH from the bundle the real registry handed out");
        return match;
    }

    @Test
    void twoAndroidStacksChatThroughTheRealBackendWithTicksFromReceipts() throws Exception {
        final RelayDevice asha = device();
        final RelayDevice ravi = device();
        final byte[] match = match(asha, ravi);
        asha.chat.send(match, text("Made it home. Thanks for tonight!"));
        assertEquals(1, asha.sync.run().sent());
        assertEquals(DeliveryMachine.State.SENT, asha.chat.messages(match).get(0).state(), "one tick from the relay");
        assertEquals(1, ravi.sync.run().shown());
        assertEquals(List.of("Made it home. Thanks for tonight!"), bodies(ravi, match));
        ravi.sync.run(); // the delivered receipt goes back
        asha.sync.run();
        assertEquals(DeliveryMachine.State.DELIVERED, asha.chat.messages(match).get(0).state(),
                "two ticks, only from Ravi's receipt");
        ravi.chat.markRead(match, true);
        ravi.sync.run();
        asha.sync.run();
        assertEquals(DeliveryMachine.State.READ, asha.chat.messages(match).get(0).state(), "blue, from the read receipt");
        ravi.chat.send(match, text("Me too. Same café Saturday?"));
        ravi.sync.run();
        assertEquals(1, asha.sync.run().shown(), "the reply opens on the responder's ratchet turn");
    }

    @Test
    void anOfflineBurstArrivesInOrderAcrossKillsOnBothSides() throws Exception {
        final RelayDevice asha = device();
        final RelayDevice ravi = device();
        final byte[] match = match(asha, ravi);
        final List<String> sent = List.of("hi", "are you still nearby?", "the queue is long", "ok see you inside");
        for (final String s : sent) {
            asha.chat.send(match, text(s)); // all inside one second, while Ravi is away
        }
        asha.kill();
        assertEquals(sent.size(), asha.sync.run().sent(), "the outbox survived the kill and went in one pass");
        ravi.kill();
        while (ravi.sync.run().moreWaiting()) {
            ravi.sync.run();
        }
        assertEquals(sent, bodies(ravi, match), "shown once each, in the order she typed them");
        ravi.kill();
        assertEquals(0, ravi.sync.run().shown(), "nothing comes back after a restart (SRS-RMT-004)");
    }

    @Test
    void afterAnUnmatchTheRealRelayTakesTheSendAndDeliversNothing() throws Exception {
        final RelayDevice asha = device();
        final RelayDevice ravi = device();
        final byte[] match = match(asha, ravi);
        assertNull(ravi.sync.forget(match), "the capability is revoked at the real relay");
        asha.chat.send(match, text("hello?"));
        assertEquals(1, asha.sync.run().sent(), "accepted: Asha learns nothing (SRS-MAT-003)");
        assertEquals(0, ravi.sync.run().shown(), "and nothing reaches Ravi (SRS-RMT-016)");
    }

    @Test
    void theOneTimePrekeyTheRegistryHandsOutIsOneThisDeviceHoldsAndItIsSpentOnce() throws Exception {
        final RelayDevice ravi = device();
        final RelayDevice asha = device();
        final int held = ravi.store.count(RecordKind.PREKEY_SECRET);
        final byte[] match = match(asha, ravi); // Asha claims one of Ravi's one-time prekeys at the registry
        asha.chat.send(match, text("first"));
        asha.sync.run();
        assertEquals(1, ravi.sync.run().shown(), "the first message opens with the claimed key");
        assertEquals(held - 1, ravi.store.count(RecordKind.PREKEY_SECRET),
                "exactly that one private half is deleted (TRS-CRY-005)");
    }
}

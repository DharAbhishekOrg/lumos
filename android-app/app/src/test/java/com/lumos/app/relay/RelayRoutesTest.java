/*
 * Lumos - android-app/app / The route row: its two formats and the ending mark.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-016, TRS-LOC-001, TRS-TST-006
 * ADRs: ADR-001, ADR-003
 * Threading: test-only
 * Notes: Format 1 rows are on devices that ran WP-05 builds; they must keep
 * reading, as open routes. The ending mark must stick: nothing that later
 * touches the route (the peer's half arriving late, a fresh registration)
 * may clear it, or an unmatched peer could be allowed back in.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.store.StoreException;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The route row. */
public class RelayRoutesTest {

    private static final SecureRandom RNG = new SecureRandom();

    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final FakeRelay relay = new FakeRelay(clock);
    private final List<AutoCloseable> open = new ArrayList<>();

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

    @Test
    void aFormatOneRowReadsAsAnOpenRoute() throws Exception {
        final byte[] match = random(16);
        final byte[] capability = random(RelayClient.KEY_SIZE);
        final ByteArrayOutputStream row = new ByteArrayOutputStream();
        row.write(1);
        for (final byte[] f : new byte[][] {match, capability, new byte[0], new byte[0], new byte[0]}) {
            row.write(f.length);
            row.write(f, 0, f.length);
        }
        final RelayRoutes.Route r = RelayRoutes.Route.decode(row.toByteArray());
        assertArrayEquals(match, r.matchId());
        assertArrayEquals(capability, r.receiveCapability());
        assertFalse(r.ending());
    }

    @Test
    void aRowRoundTripsWithItsMarkAndAnUnknownMarkIsUnreadable() throws Exception {
        final RelayRoutes.Route r = new RelayRoutes.Route(random(16), random(RelayClient.KEY_SIZE),
                random(RelayClient.ROUTING_ID_SIZE), random(RelayClient.KEY_SIZE), new byte[0], true);
        final byte[] row = r.encode();
        assertTrue(RelayRoutes.Route.decode(row).ending());
        row[row.length - 1] = 2;
        assertThrows(StoreException.class, () -> RelayRoutes.Route.decode(row));
    }

    @Test
    void theEndingMarkSticksAndTheRouteAcceptsNothing() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        final byte[] match = random(16);
        d.routes.issue(match);
        assertEquals(1, d.routes.byProof().size());
        d.routes.markEnding(match);
        d.routes.setPeer(match, random(RelayClient.ROUTING_ID_SIZE), random(RelayClient.KEY_SIZE));
        d.routes.markAllowed(match, random(RelayClient.ROUTING_ID_SIZE));
        final RelayRoutes.Route r = d.routes.forMatch(match).orElseThrow();
        assertTrue(r.ending(), "neither the peer's half nor an allowance clears it");
        assertFalse(r.canSend(), "the late peer half was not taken");
        assertTrue(d.routes.byProof().isEmpty(), "nothing arrives under an ending route");
        d.routes.markEnding(random(16));
        assertEquals(1, d.routes.all().size(), "marking an unknown match creates nothing");
    }
}

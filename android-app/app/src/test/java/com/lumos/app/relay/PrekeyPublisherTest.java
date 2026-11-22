/*
 * Lumos - android-app/app / Prekeys: private halves first, ids never reused, stock kept up.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-005, TRS-BCK-005, SRS-RMT-007, TRS-TST-001
 * ADRs: ADR-001, ADR-008
 * Threading: test-only
 * Notes: The registry's view (FakeRelay) is checked against the device's
 * disk: every one-time public the registry can hand out must have its
 * private half on the device, and no id may ever be minted twice - even
 * when the process dies with the upload's answer on its way.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.chat.PrekeySecret;
import com.lumos.app.store.RecordKind;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.x3dh.X3dh;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Prekey publishing. */
public class PrekeyPublisherTest {

    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final FakeRelay relay = new FakeRelay(clock);
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private static void assertEveryPublicHasItsPrivateHalf(final RelayDevice d, final List<Long> ids)
            throws Exception {
        for (final long id : ids) {
            assertFalse(d.store.findByNaturalKey(RecordKind.PREKEY_SECRET, PrekeySecret.naturalKey(true, id)).isEmpty(),
                    "one-time prekey " + id + " is at the registry but its private half is not on the device");
        }
    }

    @Test
    void theFirstPassPublishesASignedBundleAndTheDefaultStockWithEveryPrivateHalfStored() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        assertNull(d.sync.run().stoppedBy());
        final List<Long> ids = relay.oneTimeIds(d.routingId());
        assertEquals(LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT, ids.size());
        assertEveryPublicHasItsPrivateHalf(d, ids);

        final RelayDevice peer = new RelayDevice(clock, relay, open);
        peer.sync.run();
        final X3dh.Bundle b = RelayClient.anonymous(relay).as(peer.credential()).claim(d.routingId()).orElseThrow();
        X3dh.verifyBundle(b);
        assertEquals(d.edPub.length, b.identityEd().length);
        assertTrue(FakeRelay.same(d.edPub, b.identityEd()), "the bundle is signed by the device's identity");
    }

    @Test
    void belowTheThresholdTheStockIsToppedUpWithFreshIds() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        d.sync.run();
        final Set<Long> first = new HashSet<>(relay.oneTimeIds(d.routingId()));
        final RelayDevice peer = new RelayDevice(clock, relay, open);
        peer.sync.run();
        final RelayClient claimer = RelayClient.anonymous(relay).as(peer.credential());
        final int take = LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT - LumosConstants.PREKEY_REPLENISH_THRESHOLD_DEFAULT
                + 1;
        for (int i = 0; i < take; i++) {
            claimer.claim(d.routingId());
        }
        final List<Long> before = relay.oneTimeIds(d.routingId());
        assertTrue(before.size() < LumosConstants.PREKEY_REPLENISH_THRESHOLD_DEFAULT);

        d.sync.run();
        final List<Long> after = relay.oneTimeIds(d.routingId());
        assertEquals(LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT, after.size(), "topped back up to the default");
        final List<Long> added = new ArrayList<>(after.subList(before.size(), after.size()));
        for (final long id : added) {
            assertFalse(first.contains(id), "id " + id + " was minted before (TRS-CRY-005)");
        }
        assertEveryPublicHasItsPrivateHalf(d, after);
        assertEquals(0, d.prekeys.ensure(claimer.as(d.credential()), d.account.current().orElseThrow()),
                "a full stock needs nothing");
    }

    @Test
    void killedWithTheUploadAnswerOnItsWayNoIdIsEverMintedTwice() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        relay.fault("PUT /v1/prekeys", FakeRelay.Fault.DIE_AFTER);
        assertThrows(FakeRelay.ProcessDeath.class, d.sync::run);
        d.kill();

        assertNull(d.sync.run().stoppedBy(), "no duplicate-id refusal: the retry minted fresh ids");
        final List<Long> ids = relay.oneTimeIds(d.routingId());
        assertEquals(2 * LumosConstants.ONE_TIME_PREKEYS_UPLOADED_DEFAULT, ids.size(),
                "both batches are at the registry, the lost-answer one included");
        assertEquals(ids.size(), new HashSet<>(ids).size(), "every id is distinct");
        assertEveryPublicHasItsPrivateHalf(d, ids);
    }

    @Test
    void offlineNothingIsMinted() throws Exception {
        relay.setOffline(true);
        final RelayDevice d = new RelayDevice(clock, relay, open);
        assertEquals(RelayException.Kind.OFFLINE, d.sync.run().stoppedBy().kind());
        assertEquals(0, d.store.count(RecordKind.PREKEY_SECRET), "no keys are made for a registry it cannot reach");
        assertFalse(d.account.current().isPresent());
    }
}

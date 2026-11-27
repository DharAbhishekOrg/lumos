/*
 * Lumos - android-app/app / Live discovery end to end: two phones, real threads, real stores.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-003, SRS-NBY-005, SRS-NBY-013, SRS-NBY-017, SRS-SAF-002, TRS-ENV-001
 * ADRs: ADR-002, ADR-013
 * Threading: test-only
 * Notes: NearbyDiscovery as AppHost composes it, with only the radio and the
 * scanner replaced by an in-memory air: each phone has its own link
 * executor, its own store and its own store executor, exactly as on a
 * device, and frames hop between phones' executors as the radio would. The
 * owner's rule is checked both ways: a phone that is not visible is neither
 * found nor finding.
 */

package com.lumos.app.nearby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.ble.BleScanner;
import com.lumos.app.ble.DiscoveryEngine;
import com.lumos.app.ble.LinkRadio;
import com.lumos.app.ble.PresenceTable;
import com.lumos.app.ble.ScanSighting;
import com.lumos.app.store.RecordKind;
import com.lumos.protocol.hello.Hello;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** NearbyDiscovery over an in-memory air. */
final class NearbyDiscoveryTest {

    private static final long WAIT_MILLIS = 20_000L;

    private final PeerFixtures fixtures = new PeerFixtures();
    private final Map<String, Phone> air = new ConcurrentHashMap<>();
    private final List<AutoCloseable> closing = new ArrayList<>();

    NearbyDiscoveryTest() throws Exception {
    }

    /** One phone: its radio, scanner, executors, store and discovery. */
    private final class Phone implements LinkRadio, BleScanner {
        final String token;
        final PeerFixtures.Party party;
        final ScheduledExecutorService links = Executors.newSingleThreadScheduledExecutor();
        final ExecutorService storage = Executors.newSingleThreadExecutor();
        final Fixtures.Stack stack;
        final NearbyDiscovery discovery;
        final Map<String, String[]> routes = new ConcurrentHashMap<>();
        volatile DiscoveryEngine engine;
        volatile boolean serving;
        volatile BleScanner.Listener scanning;
        volatile int connects;
        int serverHandles;

        Phone(final String token, final int index) throws Exception {
            this.token = token;
            this.party = fixtures.peer(index, 1);
            this.stack = Fixtures.openInMemory();
            closing.add(stack);
            closing.add(links::shutdownNow);
            closing.add(storage::shutdownNow);
            this.discovery = new NearbyDiscovery(this, this, party::freshExchange, stack.store(), links, storage,
                    System::currentTimeMillis, () -> { });
            air.put(token, this);
        }

        String key() throws Exception {
            return PresenceTable.keyFor(Hello.peerId(party.ed25519Public()));
        }

        void block(final String peerKey) throws Exception {
            storage.submit(() -> {
                stack.store().put(RecordKind.BLOCK_ENTRY, null, "blocked".getBytes(StandardCharsets.UTF_8),
                        HiddenPeers.naturalKey(peerKey));
                return null;
            }).get();
        }

        // ---- LinkRadio, on this phone's link executor ----

        @Override
        public void bind(final DiscoveryEngine discovery) {
            this.engine = discovery;
        }

        @Override
        public boolean openServer() {
            serving = true;
            return true;
        }

        @Override
        public void close() {
            serving = false;
            for (final String handle : new ArrayList<>(routes.keySet())) {
                disconnect(handle);
            }
        }

        @Override
        public void connect(final String handle, final String linkToken) {
            connects++;
            final Phone target = air.get(linkToken);
            if (target == null || !target.serving) {
                links.execute(() -> engine.onLinkClosed(handle));
                return;
            }
            final String server = "s" + token + (++serverHandles);
            routes.put(handle, new String[] {linkToken, server});
            target.routes.put(server, new String[] {token, handle});
            target.links.execute(() -> target.engine.onCentralSubscribed(server, System.currentTimeMillis()));
            links.execute(() -> engine.onLinkReady(handle, System.currentTimeMillis()));
        }

        @Override
        public void send(final String handle, final byte[] frame) {
            final String[] route = routes.get(handle);
            final Phone other = route == null ? null : air.get(route[0]);
            if (other != null) {
                other.links.execute(() -> other.engine.onFrame(route[1], frame, System.currentTimeMillis()));
            }
        }

        @Override
        public void disconnect(final String handle) {
            final String[] route = routes.remove(handle);
            final Phone other = route == null ? null : air.get(route[0]);
            if (other != null && other.routes.remove(route[1]) != null) {
                other.links.execute(() -> other.engine.onLinkClosed(route[1]));
            }
        }

        // ---- BleScanner ----

        @Override
        public void start(final BleScanner.Listener listener) {
            scanning = listener;
        }

        @Override
        public void stop() {
            scanning = null;
        }
    }

    /** Every scanning phone sees every advertising (serving) phone, then waits a little. */
    private void scanRound() throws InterruptedException {
        for (final Phone scanner : air.values()) {
            final BleScanner.Listener listener = scanner.scanning;
            for (final Phone advertiser : air.values()) {
                if (listener != null && advertiser != scanner && advertiser.serving) {
                    listener.onSighting(new ScanSighting(advertiser.token, null, null, -50,
                            System.currentTimeMillis()));
                }
            }
        }
        Thread.sleep(100);
    }

    private void until(final BooleanSupplier done, final String what) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!done.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting: " + what);
            scanRound();
        }
    }

    private void onMain(final Runnable action) throws Exception {
        action.run(); // start and stop are main-thread calls; the test thread plays main
    }

    private static boolean showsOne(final Phone phone) {
        final FeedState state = phone.discovery.state();
        return state.kind() == FeedState.Kind.CARDS && state.cards().size() == 1;
    }

    @AfterEach
    void closeAll() throws Exception {
        for (final Phone phone : air.values()) {
            phone.discovery.stop();
        }
        for (final AutoCloseable c : closing) {
            c.close();
        }
    }

    @Test
    void twoVisiblePhonesSeeEachOtherAndStopForgetsAtOnce() throws Exception {
        final Phone a = new Phone("a", 1);
        final Phone b = new Phone("b", 2);
        assertEquals(FeedState.Kind.NOT_VISIBLE, a.discovery.state().kind(), "not started: not visible");
        onMain(a.discovery::start);
        onMain(b.discovery::start);
        until(() -> showsOne(a) && showsOne(b), "both phones show one card");
        assertEquals(b.key(), a.discovery.state().cards().get(0).peerKey(), "a sees b's real identity");
        assertEquals(a.key(), b.discovery.state().cards().get(0).peerKey(), "b sees a's");

        onMain(a.discovery::stop);
        assertEquals(FeedState.Kind.NOT_VISIBLE, a.discovery.state().kind(), "stop empties the screen at once");
        a.links.submit(() -> null).get(5, TimeUnit.SECONDS);
        assertTrue(a.routes.isEmpty(), "and every link is closed");
        assertTrue(!a.serving && a.scanning == null, "and nothing serves or scans");
    }

    @Test
    void aPhoneThatIsNotVisibleIsNeitherFoundNorFinding() throws Exception {
        final Phone a = new Phone("a", 1);
        final Phone hidden = new Phone("h", 2);
        onMain(a.discovery::start);
        for (int i = 0; i < 30; i++) {
            scanRound();
        }
        assertTrue(a.discovery.state().cards().isEmpty(), "a hidden phone is not found");
        assertEquals(0, hidden.connects, "and it looks for no one");
        assertEquals(FeedState.Kind.NOT_VISIBLE, hidden.discovery.state().kind());
    }

    @Test
    void aBlockedPersonIsNotShownAndGetsNoCardBack() throws Exception {
        final Phone a = new Phone("a", 1);
        final Phone b = new Phone("b", 2);
        a.block(b.key());
        onMain(a.discovery::start);
        onMain(b.discovery::start);
        for (int i = 0; i < 40; i++) {
            scanRound();
        }
        assertTrue(a.discovery.state().cards().isEmpty(), "a never shows the person they blocked");
        assertTrue(b.discovery.state().cards().isEmpty(),
                "and the blocked person never gets a card of a (SRS-SAF-002)");
    }
}

/*
 * Lumos - android-app/app / A HELLO from this install's real stored identity verifies at a peer.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, SRS-IDN-002, SRS-NBY-005
 * ADRs: ADR-002, ADR-008, ADR-013
 * Threading: test-only
 * Notes: The identity is made exactly as onboarding makes it (generator,
 * software KEK, unlock), then a whole link runs between it and a fixture
 * peer: both ends finish, the peer learns this install's identity (the
 * Ed25519 key's peer id) and the empty fields read as empty.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lumos.app.identity.ChatIdentity;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.JcaIdentityCrypto;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.nearby.PeerFixtures;
import com.lumos.protocol.hello.Hello;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** NearbyIdentity over a real generated identity. */
final class NearbyIdentityTest {

    private static final long T0 = 1_000_000L;
    private static final long TIMEOUT = 10_000L;
    private final Deque<byte[]> toPeripheral = new ArrayDeque<>();
    private final Deque<byte[]> toCentral = new ArrayDeque<>();
    private int nonce;

    private byte[] nonce() {
        final byte[] n = new byte[Hello.NONCE_LEN];
        nonce++;
        n[0] = (byte) nonce;
        return n;
    }

    @Test
    void aLinkFromTheStoredIdentityFinishesAndNamesThisInstall() throws Exception {
        final SoftwareKekStore kek = SoftwareKekStore.create();
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
        try (ChatIdentity chat = ChatIdentity.unlock(record, kek, new JcaIdentityCrypto())) {
            final NearbyIdentity me = new NearbyIdentity(record, chat::sign);
            final PeerFixtures.Party peer = new PeerFixtures().peer(7, 1);

            final LinkSession central = new LinkSession(LinkSession.Role.CENTRAL, me.freshExchange(),
                    toPeripheral::add, this::nonce, TIMEOUT);
            final LinkSession peripheral = new LinkSession(LinkSession.Role.PERIPHERAL, peer.freshExchange(),
                    toCentral::add, this::nonce, TIMEOUT);
            peripheral.open(T0);
            central.open(T0);
            while (!toPeripheral.isEmpty() || !toCentral.isEmpty()) {
                if (!toPeripheral.isEmpty()) {
                    peripheral.onFrame(toPeripheral.poll(), T0);
                }
                if (!toCentral.isEmpty()) {
                    central.onFrame(toCentral.poll(), T0);
                }
            }
            assertEquals(LinkSession.State.DONE, central.state(), "this install verified the peer");
            assertEquals(LinkSession.State.DONE, peripheral.state(), "and the peer verified this install");
            assertArrayEquals(Hello.peerId(record.ed25519Public()), peripheral.peer().id(),
                    "the peer sees this install's stored identity");
            assertEquals(NearbyIdentity.NO_CAPABILITIES, peripheral.peer().fields().capabilityBits());
            assertArrayEquals(new byte[Hello.EID_LEN], peripheral.peer().fields().eid(), "hintless: zero EID");
            assertArrayEquals(record.x25519Public(), peripheral.peer().fields().x25519Public(),
                    "the bound identity-DH key travels (ADR-008)");
        }
    }
}

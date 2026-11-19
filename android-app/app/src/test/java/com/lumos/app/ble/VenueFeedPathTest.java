/*
 * Lumos - android-app/app / 100+ synthetic peers through the whole feed path.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-ENV-001, TRS-ENV-002, SRS-NBY-006, SRS-NBY-007, TRS-TST-001
 * ADRs: ADR-002, ADR-008
 * Threading: test-only
 * Notes: S3 proved triage under venue load and S4 proved one exchange; this
 * is the WP-03 closing proof that the LAYERS compose: 110 genuine advertisers
 * plus noise go sighting -> triage -> budgeted connect waves -> REAL dual
 * HELLO (JCA-signed, every signature verified) -> presence, and every peer
 * arrives while no wave ever exceeds MAX_CONCURRENT_GATT_LINKS. The EID
 * rotation pass re-advertises a tenth of the venue under fresh EIDs and
 * re-exchanges; presence must not grow, because the card key is identity, not
 * the rotating address (SRS-NBY-006). Noise dies structurally and can never
 * reach presence (TRS-ENV-002), and the register age-out empties the venue
 * at the bound (SRS-NBY-007). What a real floor adds - interference, OEM
 * throttling - is the G-01 physical half of the same requirements.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.adv.Adv;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The composed discovery pipeline under synthetic venue load. */
final class VenueFeedPathTest {

    private static final int GENUINE_PEERS =
            LumosConstants.NEARBY_SYNTHETIC_LOAD_TARGET_DEFAULT + 10;
    private static final int NOISE_SIGHTINGS = 12;
    private static final int ROTATING_PEERS = 11;
    private static final long NOW = 1_755_561_600_000L;

    /** A venue-goer: one identity, fresh exchange objects per link. */
    private static final class Party {

        private final Hello.Fields fields;
        private final PrivateKey edPrivate;

        Party(final KeyPairGenerator generator, final int index) throws Exception {
            final KeyPair pair = generator.generateKeyPair();
            this.edPrivate = pair.getPrivate();
            final byte[] spki = pair.getPublic().getEncoded();
            final byte[] edPublic = Arrays.copyOfRange(spki, spki.length - 32, spki.length);

            final byte[] scalar = new byte[32];
            Arrays.fill(scalar, (byte) (1 + (index % 250)));
            final byte[] xPublic = LumosCrypto.x25519PublicFromPrivate(scalar);
            final byte[] binding = sign(LumosCrypto.concat(
                    LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE
                            .getBytes(StandardCharsets.UTF_8),
                    xPublic));
            final byte[] eid = new byte[Hello.EID_LEN];
            eid[0] = (byte) index;
            eid[1] = (byte) (index >> 8);
            this.fields = new Hello.Fields(LumosConstants.PROTOCOL_VERSION_ENCODED, 1, 0L,
                    eid, edPublic, xPublic, binding, new byte[Hello.NONCE_LEN]);
        }

        HelloExchange freshExchange() {
            return new HelloExchange(this::sign, fields);
        }

        private byte[] sign(final byte[] message) {
            try {
                final Signature s = Signature.getInstance("Ed25519");
                s.initSign(edPrivate);
                s.update(message);
                return s.sign();
            } catch (final java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static byte[] kAdvFor(final int peer) {
        final byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (peer * 37 + i + 11);
        }
        return k;
    }

    private static byte[] nonceFor(final int n) {
        final byte[] nonce = new byte[Hello.NONCE_LEN];
        nonce[0] = (byte) n;
        nonce[1] = (byte) (n >> 8);
        nonce[2] = (byte) (n >> 16);
        return nonce;
    }

    /** One budgeted wave later, one authenticated card per connect. */
    private static int connectWave(final DiscoveryTriage triage, final PresenceTable presence,
            final Party me, final Map<String, Party> byAddress, final int nonceBase)
            throws Exception {
        final List<DiscoveryTriage.Candidate> wave = triage.nextConnects(0);
        assertTrue(wave.size() <= LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT,
                "no wave may exceed the register link budget (TRS-ENV-001)");
        int i = 0;
        for (final DiscoveryTriage.Candidate candidate : wave) {
            assertTrue(triage.remove(candidate.key()),
                    "a spent candidate leaves rotation so the next wave moves on");
            final Party peer = byAddress.get(candidate.linkToken());
            final HelloExchange mine = me.freshExchange();
            final byte[] hello = mine.start(nonceFor(nonceBase + i++));
            final byte[] ack = peer.freshExchange().onHello(hello);
            presence.record(mine.onAck(ack), NOW);
        }
        return wave.size();
    }

    @Test
    void aFullVenueFlowsSightingToTriageToHelloToPresenceUnderTheBudget() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(255, new SecureRandom());
        final Party me = new Party(generator, 9999);

        final DiscoveryTriage triage = new DiscoveryTriage();
        final PresenceTable presence = new PresenceTable();
        final Map<String, Party> byAddress = new HashMap<>();

        // The venue: 110 genuine hinted advertisers across the RSSI range,
        // built through the vector-pinned Adv code, plus structural noise.
        for (int p = 0; p < GENUINE_PEERS; p++) {
            final String address = "venue:" + p;
            byAddress.put(address, new Party(generator, p));
            final byte[] hint = Adv.build(kAdvFor(p), 0, 1);
            triage.ingest(new ScanSighting(address, hint, null, -40 - (p % 55), NOW));
        }
        for (int n = 0; n < NOISE_SIGHTINGS; n++) {
            triage.ingest(new ScanSighting("noise:" + n, new byte[] {9, 9, 9}, null, -35, NOW));
        }
        assertEquals(GENUINE_PEERS, triage.size(), "every genuine peer triages, noise does not");
        assertEquals(NOISE_SIGHTINGS, triage.structurallyDiscarded());

        // Budgeted waves until the whole venue is authenticated.
        int waves = 0;
        int nonce = 0;
        while (presence.size() < GENUINE_PEERS) {
            final int connected = connectWave(triage, presence, me, byAddress, nonce);
            assertTrue(connected > 0, "the pipeline must make progress every wave");
            nonce += connected;
            waves++;
        }
        assertEquals(GENUINE_PEERS, presence.size(),
                "every genuine advertiser ends up as exactly one card");
        final int budget = LumosConstants.MAX_CONCURRENT_GATT_LINKS_DEFAULT;
        assertTrue(waves >= (GENUINE_PEERS + budget - 1) / budget,
                "a bounded budget cannot swallow a venue in fewer waves than the arithmetic");

        // EID rotation: a tenth of the venue re-advertises under fresh EIDs
        // and re-exchanges; identity-keyed presence must not grow.
        final Set<String> cardsBefore = new HashSet<>();
        for (final PresenceTable.Card card : presence.cards()) {
            cardsBefore.add(card.peerKey());
        }
        for (int p = 0; p < ROTATING_PEERS; p++) {
            final byte[] rotated = Adv.build(kAdvFor(p), 0, 2);
            triage.ingest(new ScanSighting("venue:" + p, rotated, null, -45, NOW + 1000));
        }
        assertEquals(ROTATING_PEERS, triage.size(), "rotated EIDs are fresh candidates");
        while (triage.size() > 0) {
            nonce += connectWave(triage, presence, me, byAddress, nonce);
        }
        assertEquals(GENUINE_PEERS, presence.size(),
                "an EID rotation is the same person, never a second card (SRS-NBY-006)");
        final Set<String> cardsAfter = new HashSet<>();
        for (final PresenceTable.Card card : presence.cards()) {
            cardsAfter.add(card.peerKey());
        }
        assertEquals(cardsBefore, cardsAfter, "the card keys are identities, not addresses");

        // The register age-out empties the venue at the bound and not before.
        final long bound = LumosConstants.PRESENCE_AGEOUT_SEC_DEFAULT * 1000L;
        assertEquals(0, presence.prune(NOW + bound), "the last millisecond inside keeps cards");
        assertEquals(GENUINE_PEERS, presence.size());
        assertEquals(GENUINE_PEERS, presence.prune(NOW + bound + 1001),
                "past the bound every stale card goes (SRS-NBY-007)");
        assertEquals(0, presence.size());
    }
}

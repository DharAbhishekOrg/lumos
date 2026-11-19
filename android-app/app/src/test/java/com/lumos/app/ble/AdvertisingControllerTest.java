/*
 * Lumos - android-app/app / Advertising rotation and the degraded downgrade.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-009, SRS-IDN-004, TRS-AND-005, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: The unlinkability requirement as an executed property: across an EID
 * rotation boundary the controller re-advertises with a DIFFERENT payload, and
 * each payload is byte-identical to what Adv.build - the vector-pinned code -
 * produces for that epoch. The downgrade test is TRS-AND-005's OEM reality:
 * a stack that cannot carry the hint gets a UUID-only peer, not a dead one.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.adv.Adv;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** AdvertisingController, held to the epoch schedule. */
final class AdvertisingControllerTest {

    /** Records every start and can script failures. */
    private static final class FakeAdvertiser implements BleAdvertiser {
        private final List<byte[]> hints = new ArrayList<>();
        private final List<byte[]> scanResponses = new ArrayList<>();
        private int degradedStarts;
        private int stops;
        private Listener lastHintListener;

        @Override
        public void startHint(final byte[] serviceData, final byte[] scanResponse,
                final Listener listener) {
            hints.add(serviceData.clone());
            scanResponses.add(scanResponse.clone());
            lastHintListener = listener;
        }

        @Override
        public void startDegraded(final Listener listener) {
            degradedStarts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static final class FailureSink implements BleAdvertiser.Listener {
        private BlePermissions.AdvertiseFailure failure;

        @Override
        public void onAdvertiseFailed(final BlePermissions.AdvertiseFailure state) {
            failure = state;
        }
    }

    private final FakeAdvertiser advertiser = new FakeAdvertiser();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final AdvertisingController controller =
            new AdvertisingController(advertiser, scheduler, scheduler::nowSeconds);

    private final byte[] kAdv = deterministicKey();

    private static byte[] deterministicKey() {
        final byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (i * 7 + 3);
        }
        return k;
    }

    @Test
    void theHintIsTheVectorPinnedBuildForTheCurrentEpoch() throws Exception {
        controller.startHint(kAdv, 0, 5, 9L, new FailureSink());
        final long epoch = Adv.epoch(scheduler.nowSeconds(), LumosConstants.EID_ROTATION_SEC_DEFAULT);
        assertEquals(1, advertiser.hints.size());
        assertArrayEquals(Adv.build(kAdv, 0, epoch), advertiser.hints.get(0),
                "the radio must carry exactly what the conformance suite pins");
        assertArrayEquals(Adv.buildScanResponse(5, 9L), advertiser.scanResponses.get(0));
    }

    @Test
    void rotationReAdvertisesWithAnUnlinkablePayload() throws Exception {
        controller.startHint(kAdv, 0, 0, 0L, new FailureSink());
        final long rotationMillis = (long) LumosConstants.EID_ROTATION_SEC_DEFAULT * 1000;
        scheduler.advanceBy(rotationMillis);

        assertEquals(2, advertiser.hints.size(), "the boundary must re-advertise");
        final byte[] before = advertiser.hints.get(0);
        final byte[] after = advertiser.hints.get(1);
        assertFalse(java.util.Arrays.equals(before, after),
                "a static payload would link the device across epochs (SRS-IDN-004)");
        final long epochAfter =
                Adv.epoch(scheduler.nowSeconds(), LumosConstants.EID_ROTATION_SEC_DEFAULT);
        assertArrayEquals(Adv.build(kAdv, 0, epochAfter), after);
    }

    @Test
    void anUnsupportedStackDowngradesToDegradedInsteadOfDying() throws Exception {
        final FailureSink sink = new FailureSink();
        controller.startHint(kAdv, 0, 0, 0L, sink);
        advertiser.lastHintListener.onAdvertiseFailed(
                BlePermissions.AdvertiseFailure.FEATURE_UNSUPPORTED);

        assertTrue(controller.degraded(), "the downgrade is automatic (ADR-002)");
        assertEquals(1, advertiser.degradedStarts);
        assertNull(sink.failure, "a successful downgrade is not a failure");

        final int hintsBefore = advertiser.hints.size();
        scheduler.advanceBy((long) LumosConstants.EID_ROTATION_SEC_DEFAULT * 2000);
        assertEquals(hintsBefore, advertiser.hints.size(),
                "rotation must stop once degraded - there is no hint to rotate");
    }

    @Test
    void otherFailuresReachTheListenerTyped() throws Exception {
        final FailureSink sink = new FailureSink();
        controller.startHint(kAdv, 0, 0, 0L, sink);
        advertiser.lastHintListener.onAdvertiseFailed(
                BlePermissions.AdvertiseFailure.TOO_MANY_ADVERTISERS);
        assertEquals(BlePermissions.AdvertiseFailure.TOO_MANY_ADVERTISERS, sink.failure);
        assertFalse(controller.degraded());
    }

    @Test
    void stopCancelsRotation() throws Exception {
        controller.startHint(kAdv, 0, 0, 0L, new FailureSink());
        controller.stop();
        scheduler.advanceBy((long) LumosConstants.EID_ROTATION_SEC_DEFAULT * 3000);
        assertEquals(1, advertiser.hints.size(), "no rotation may fire after stop");
    }
}

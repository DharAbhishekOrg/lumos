/*
 * Lumos - android-app/app / Advertising lifecycle: hint rotation and degraded mode.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-009, TRS-PROTO-017, SRS-IDN-004, TRS-AND-005
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: The Android half of ADR-002's asymmetric advertising. In hint mode the
 * controller builds adv_v1 for the CURRENT epoch through the shared Adv class -
 * the same bytes the conformance suite pins - and re-advertises at every EID
 * rotation boundary, so a static payload can never link a device across epochs
 * (SRS-IDN-004). The clock is injected and rotation runs on the scheduler seam:
 * nothing here reads time on its own. Each start builds an immutable session
 * object that the rotation closure carries, so state can never be read
 * half-initialised.
 *
 * Degraded UUID-only advertising is a SUPPORTED MODE, entered two ways: asked
 * for directly (OEM stacks that refuse service data), or as the automatic
 * downgrade when the platform answers hint-mode with DATA_TOO_LARGE or
 * FEATURE_UNSUPPORTED. A degraded Lumos peer is discovered like every iOS
 * peer and authenticated in HELLO like every peer of any kind
 * (TRS-PROTO-017); the downgrade costs reach, never correctness.
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.adv.Adv;
import com.lumos.protocol.wire.Frame;
import java.util.Arrays;
import java.util.function.LongSupplier;

/** Runs Lumos advertising: epoch-rotated hint mode or degraded UUID-only. */
public final class AdvertisingController {

    /** One hint-mode session's immutable inputs. */
    private static final class HintSession {

        private final byte[] kAdv;
        private final int modeFlags;
        private final byte[] scanResponse;
        private final BleAdvertiser.Listener listener;

        private HintSession(final byte[] kAdv, final int modeFlags, final byte[] scanResponse,
                final BleAdvertiser.Listener listener) {
            this.kAdv = kAdv;
            this.modeFlags = modeFlags;
            this.scanResponse = scanResponse;
            this.listener = listener;
        }
    }

    private final BleAdvertiser advertiser;
    private final TaskScheduler scheduler;
    private final LongSupplier unixSecondsClock;

    private HintSession session;
    private TaskScheduler.Scheduled rotationHandle;
    private boolean running;
    private boolean degraded;

    /**
     * @param advertiser the advertising seam
     * @param scheduler the deferred-execution seam rotation runs on
     * @param unixSecondsClock the injected clock, unix seconds
     */
    public AdvertisingController(final BleAdvertiser advertiser, final TaskScheduler scheduler,
            final LongSupplier unixSecondsClock) {
        this.advertiser = advertiser;
        this.scheduler = scheduler;
        this.unixSecondsClock = unixSecondsClock;
    }

    /** @return whether the controller is advertising in degraded UUID-only mode */
    public boolean degraded() {
        return degraded;
    }

    /**
     * Starts hint-mode advertising, rotating at every EID epoch boundary.
     *
     * @param advertisementKey K_adv; the caller keeps ownership
     * @param flags adv_v1 mode flags for this session
     * @param capabilityBits scan-response capability bits
     * @param venueHint scan-response venue hint
     * @param listener where advertising failures go, after any downgrade
     * @throws Frame.WireException when the mode flags set a reserved bit
     */
    public void startHint(final byte[] advertisementKey, final int flags, final int capabilityBits,
            final long venueHint, final BleAdvertiser.Listener listener) throws Frame.WireException {
        stop();
        final HintSession started = new HintSession(advertisementKey.clone(), flags,
                Adv.buildScanResponse(capabilityBits, venueHint), listener);
        this.session = started;
        this.running = true;
        this.degraded = false;
        advertiseCurrentEpoch(started);
    }

    /**
     * Starts degraded UUID-only advertising directly (ADR-002 supported mode).
     *
     * @param listener where advertising failures go
     */
    public void startDegraded(final BleAdvertiser.Listener listener) {
        stop();
        this.running = true;
        this.degraded = true;
        advertiser.startDegraded(listener);
    }

    /** Stops advertising and rotation; idempotent. */
    public void stop() {
        running = false;
        degraded = false;
        if (rotationHandle != null) {
            rotationHandle.cancel();
            rotationHandle = null;
        }
        if (session != null) {
            Arrays.fill(session.kAdv, (byte) 0);
            session = null;
        }
        advertiser.stop();
    }

    private void advertiseCurrentEpoch(final HintSession active) throws Frame.WireException {
        if (!running || session != active) {
            return;
        }
        final long nowSeconds = unixSecondsClock.getAsLong();
        final long epoch = Adv.epoch(nowSeconds, LumosConstants.EID_ROTATION_SEC_DEFAULT);
        final byte[] hint = Adv.build(active.kAdv, active.modeFlags, epoch);

        advertiser.stop();
        advertiser.startHint(hint, active.scanResponse, state -> {
            if (state == BlePermissions.AdvertiseFailure.DATA_TOO_LARGE
                    || state == BlePermissions.AdvertiseFailure.FEATURE_UNSUPPORTED) {
                // The automatic downgrade: this stack cannot carry the hint,
                // so advertise like an iOS peer instead of not at all.
                degraded = true;
                if (rotationHandle != null) {
                    rotationHandle.cancel();
                    rotationHandle = null;
                }
                advertiser.stop();
                advertiser.startDegraded(active.listener);
                return;
            }
            active.listener.onAdvertiseFailed(state);
        });

        final long boundarySeconds = (epoch + 1) * LumosConstants.EID_ROTATION_SEC_DEFAULT;
        rotationHandle = scheduler.schedule(() -> {
            try {
                advertiseCurrentEpoch(active);
            } catch (final Frame.WireException impossible) {
                // The flags were validated on the first build; a rotation
                // cannot invent a reserved bit. Surface it as a failure
                // rather than swallowing the impossible.
                active.listener.onAdvertiseFailed(BlePermissions.AdvertiseFailure.INTERNAL_ERROR);
            }
        }, Math.max(1, (boundarySeconds - nowSeconds) * 1000));
    }
}

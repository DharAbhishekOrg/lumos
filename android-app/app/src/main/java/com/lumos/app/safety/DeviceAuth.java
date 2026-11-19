/*
 * Lumos - android-app/app / The device-authentication seam and what must use it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-009, SRS-ONB-007, SRS-IDN-003, SRS-NFR-SEC-003
 * ADRs: ADR-010
 * Threading: main-safe
 * Notes: ADR-010's layered design in one seam: keys are NOT auth-gated (the
 * radio must decrypt with the device locked), so the person-present check
 * lives at the moments that deserve it - unlocking the app, and the three
 * irreversible actions the owner ruling names: emergency wipe, RecoveryRecord
 * export and identity reset. The platform prompt (BiometricPrompt / device
 * credential) sits behind this interface so every policy above it executes in
 * JVM tests, and requiresDeviceAuth is the policy TABLE - a new purpose added
 * without a row fails the policy test rather than quietly skipping the
 * prompt.
 */

package com.lumos.app.safety;

import java.util.Objects;

/** Presents the platform device-authentication prompt (ADR-010). */
public interface DeviceAuth {

    /** Why authentication is being asked for. */
    enum Purpose {

        /** Unlocking the app past App Lock. */
        APP_UNLOCK,

        /** Destroying everything on the device (SRS-SAF-009). */
        EMERGENCY_WIPE,

        /** Exporting the content-only RecoveryRecord (SRS-ONB-007). */
        RECOVERY_EXPORT,

        /** Discarding this identity for a new one (SRS-IDN-003). */
        IDENTITY_RESET
    }

    /** What the prompt came back with. */
    enum Result {

        /** The person proved presence with biometric or device credential. */
        CONFIRMED,

        /** The person dismissed or failed the prompt. */
        NOT_CONFIRMED,

        /**
         * The device has no credential set up at all. ADR-010's residual-risk
         * note applies: the protection a device cannot offer is reported, not
         * silently skipped - the caller decides what the purpose allows.
         */
        UNAVAILABLE
    }

    /**
     * Whether a purpose requires the device-auth prompt at the moment of the
     * action. Under ADR-010 every purpose in the enum does, by construction:
     * being listed here IS the ruling, so a flow that wants to skip the
     * prompt has no purpose to pass and nothing to call. The method exists so
     * call sites ask the policy rather than remember it.
     *
     * @param purpose the purpose being attempted
     * @return true; the enum is the table
     */
    static boolean requiresDeviceAuth(final Purpose purpose) {
        Objects.requireNonNull(purpose, "purpose");
        return true;
    }

    /**
     * Presents the platform prompt and reports what happened.
     *
     * @param purpose why authentication is being asked for
     * @return the outcome; never null
     */
    Result confirm(Purpose purpose);
}

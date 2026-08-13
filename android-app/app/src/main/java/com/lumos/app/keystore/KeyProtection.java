/*
 * Lumos - android-app/app / The protection tier a KEK actually obtained.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-NFR-SEC-003
 * ADRs: ADR-003, ADR-010
 * Threading: any
 * Notes: Threat model LT-05: a StrongBox probe that fails soft is worse than no
 * probe, because the app then reports hardware protection it does not have. The
 * tier is therefore a RECORDED FACT about the installation - established at key
 * creation, persisted, and read by anything that reasons about key protection.
 * Nothing may assume the best case.
 */

package com.lumos.app.keystore;

/**
 * The protection tier the key-encryption key actually obtained, recorded at
 * creation per the capability-probe rule of ADR-010.
 */
public enum KeyProtection {

    /** Dedicated secure element (Android StrongBox). The strongest tier. */
    STRONGBOX,

    /** Hardware-backed trusted execution environment keystore. */
    TEE,

    /**
     * Software fallback with no hardware binding. On a device this tier means
     * the probe found no hardware keystore; in CI and on the JVM it is the only
     * tier that exists. Hardware-backed behaviour is owner-verified on physical
     * devices and never simulated (WP-02 kickoff, owner-executed row).
     */
    SOFTWARE_FALLBACK;

    /**
     * Reports whether this tier is backed by hardware the OS cannot bypass.
     *
     * @return true for {@link #STRONGBOX} and {@link #TEE}
     */
    public boolean isHardwareBacked() {
        return this != SOFTWARE_FALLBACK;
    }
}

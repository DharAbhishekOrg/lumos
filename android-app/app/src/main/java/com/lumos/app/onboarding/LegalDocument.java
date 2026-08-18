/*
 * Lumos - android-app/app / The legal documents onboarding must show.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-002
 * ADRs: -
 * Threading: any
 * Notes: SRS-ONB-002 names four documents, so there are four enum constants
 * and a test that asserts the count - a fifth document added to docs/legal
 * without being named here would ship unshown and unaccepted, which is the
 * failure mode worth catching.
 *
 * The asset name is the file's real name under docs/legal, which the Android
 * build packages into the APK as an asset directly rather than copying. One
 * file is the shipped artefact, the displayed text, and the version of record.
 */

package com.lumos.app.onboarding;

/** A document the user must be shown and must accept before onboarding completes. */
public enum LegalDocument {

    /** The agreement itself. */
    TERMS_OF_USE("terms-of-use.md"),

    /** What is collected, what leaves the device, and what never does. */
    PRIVACY_NOTICE("privacy-notice.md"),

    /** How people are expected to behave, incorporated by the terms. */
    COMMUNITY_RULES("community-rules.md"),

    /** What the phone's Bluetooth radio transmits and listens for (ADR-002). */
    LOCAL_RADIO_DISCLOSURE("local-radio-disclosure.md");

    private final String assetName;

    LegalDocument(final String assetName) {
        this.assetName = assetName;
    }

    /**
     * The document's file name, under docs/legal and inside the APK's assets.
     *
     * @return the file name
     */
    public String assetName() {
        return assetName;
    }
}

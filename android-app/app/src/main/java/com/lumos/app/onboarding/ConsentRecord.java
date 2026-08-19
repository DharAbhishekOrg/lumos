/*
 * Lumos - android-app/app / The consent evidence, as it is stored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002
 * ADRs: -
 * Threading: any
 * Notes: Privacy data map Sec. 2.3, expressed as a type. Three facts and no
 * more: that the age gate was passed and when, what minimum it was judged
 * against, and which version of each document was accepted.
 *
 * There is no date of birth here, and no field one could be put in without
 * changing this class - which is the point. AgeGate returns a decision, this
 * record stores the decision, and ConsentCodecTest asserts a birth date does
 * not survive into the encoded bytes.
 *
 * Timestamps are UTC milliseconds (TRS-LOC-010), supplied by the caller's clock
 * rather than read here, so "what was recorded" is testable without waiting.
 */

package com.lumos.app.onboarding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** What the user affirmed, and to which versions. */
public final class ConsentRecord {

    private final long affirmedAtUtcMillis;
    private final int minimumAgeYears;
    private final Map<LegalDocument, String> acceptedVersions;

    /**
     * Assembles a record.
     *
     * @param affirmedAtUtcMillis when the user completed the gate and accepted
     * @param minimumAgeYears the adult minimum in force at that moment
     * @param acceptedVersions the version accepted for every document
     */
    public ConsentRecord(final long affirmedAtUtcMillis, final int minimumAgeYears,
            final Map<LegalDocument, String> acceptedVersions) {
        if (acceptedVersions == null) {
            throw new IllegalArgumentException("accepted versions are required");
        }
        this.affirmedAtUtcMillis = affirmedAtUtcMillis;
        this.minimumAgeYears = minimumAgeYears;
        final Map<LegalDocument, String> copy = new EnumMap<>(LegalDocument.class);
        copy.putAll(acceptedVersions);
        this.acceptedVersions = Collections.unmodifiableMap(copy);
    }

    /**
     * When the age gate was passed and the documents accepted.
     *
     * @return UTC milliseconds since the epoch
     */
    public long affirmedAtUtcMillis() {
        return affirmedAtUtcMillis;
    }

    /**
     * The adult minimum this affirmation was judged against.
     *
     * @return the minimum in whole years
     */
    public int minimumAgeYears() {
        return minimumAgeYears;
    }

    /**
     * The accepted version of each document.
     *
     * @return an unmodifiable map
     */
    public Map<LegalDocument, String> acceptedVersions() {
        return acceptedVersions;
    }

    /**
     * The documents whose current version differs from what was accepted.
     *
     * <p>A document missing from this record counts as outdated: consent that
     * was never given is not consent that is still current. SRS-ONB-002 asks
     * for the CURRENT documents to be displayed, and this is the question a
     * launch check asks to know whether to display them again.
     *
     * @param current the versions the shipped documents declare now
     * @return the documents needing acceptance again, in enum order
     */
    public List<LegalDocument> outdatedAgainst(final Map<LegalDocument, String> current) {
        final List<LegalDocument> stale = new ArrayList<>();
        for (final LegalDocument document : LegalDocument.values()) {
            final String accepted = acceptedVersions.get(document);
            final String now = current.get(document);
            if (accepted == null || now == null || !accepted.equals(now)) {
                stale.add(document);
            }
        }
        return Collections.unmodifiableList(stale);
    }
}

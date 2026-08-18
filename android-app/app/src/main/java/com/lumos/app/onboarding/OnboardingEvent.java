/*
 * Lumos - android-app/app / The typed events onboarding accepts.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006
 * ADRs: -
 * Threading: any
 * Notes: A sealed hierarchy rather than an enum, because two of these events
 * carry data and TRS Sec. 24.2 asks for typed events. Sealed means the machine's
 * dispatch is exhaustive by compilation: a new event cannot be added without
 * the compiler pointing at every place that must decide what to do with it.
 *
 * AgeDeclared carries the DECISION, not the date of birth. That is deliberate
 * and it is the privacy shape the data map (Sec. 2.3) specifies: the affirmation
 * is the evidence that is kept, and the date it was computed from is evaluated
 * by AgeGate and discarded. An event carrying a LocalDate would put the date
 * one field access away from the record that gets persisted.
 */

package com.lumos.app.onboarding;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** An input to {@link OnboardingMachine}. */
public sealed interface OnboardingEvent {

    /**
     * The user answered the age gate, and {@link AgeGate} judged the answer.
     *
     * @param decision the gate's verdict; never the date it was computed from
     */
    record AgeDeclared(AgeGate.Decision decision) implements OnboardingEvent {

        /**
         * Rejects a null decision, which would otherwise reach the machine as
         * an unanswerable question.
         *
         * @param decision the gate's verdict
         */
        public AgeDeclared {
            if (decision == null) {
                throw new IllegalArgumentException("an age gate event needs a decision");
            }
        }
    }

    /**
     * The user accepted the legal documents at the versions shown to them.
     *
     * @param versions the version accepted for each of the four documents
     */
    record LegalDocumentsAccepted(Map<LegalDocument, String> versions) implements OnboardingEvent {

        /**
         * Copies the version map and refuses an incomplete one.
         *
         * <p>Every document must be present. A partial acceptance is not a
         * weaker consent, it is an unknown one, and SRS-ONB-002 asks for the
         * accepted versions rather than some of them.
         *
         * @param versions the version accepted for each document
         */
        public LegalDocumentsAccepted {
            if (versions == null || versions.size() != LegalDocument.values().length) {
                throw new IllegalArgumentException(
                        "consent must name a version for every legal document");
            }
            final Map<LegalDocument, String> copy = new EnumMap<>(LegalDocument.class);
            copy.putAll(versions);
            versions = Collections.unmodifiableMap(copy);
        }
    }

    /** The local cryptographic identity now exists (SRS-ONB-006). */
    record IdentityCreated() implements OnboardingEvent { }

    /**
     * Onboarding starts again from the age gate.
     *
     * <p>The only way out of {@link OnboardingState#BLOCKED_UNDER_AGE}, and the
     * event an identity reset raises (SRS-IDN-003).
     */
    record RestartRequested() implements OnboardingEvent { }
}

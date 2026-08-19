/*
 * Lumos - android-app/app / The states of onboarding.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006
 * ADRs: -
 * Threading: any
 * Notes: TRS Sec. 24.2 requires explicit state machines with typed events and
 * forbids boolean-flag state. Onboarding is the first flow in the application
 * that has states at all, and it is the one where a stray boolean would be
 * worst: "hasConsented && isAdult && hasIdentity" is three flags with eight
 * combinations, five of which are nonsense, and nothing in the type system
 * would stop a screen from reading two of them and drawing the third wrong.
 *
 * BLOCKED_UNDER_AGE is a state and not an error return. A refusal that is only
 * a returned value is a refusal a caller can ignore; a refusal that is the
 * machine's state means the surface gate refuses too, and keeps refusing.
 */

package com.lumos.app.onboarding;

/** Where a user is in onboarding. */
public enum OnboardingState {

    /** Nothing has happened yet: the age gate is the only surface (SRS-ONB-001). */
    AGE_GATE,

    /** Age affirmed; the legal documents must be shown and accepted (SRS-ONB-002). */
    LEGAL_CONSENT,

    /** Documents accepted; the local identity is being created (SRS-ONB-006). */
    IDENTITY_CREATION,

    /** Onboarding is done and the application's surfaces are open. */
    COMPLETE,

    /**
     * The declared age was below the adult minimum.
     *
     * <p>Terminal for this device until onboarding is restarted from the
     * beginning. No surface is reachable from here, including the onboarding
     * steps that follow the gate.
     */
    BLOCKED_UNDER_AGE;

    /**
     * Whether onboarding has finished.
     *
     * @return true only in {@link #COMPLETE}
     */
    public boolean isComplete() {
        return this == COMPLETE;
    }
}

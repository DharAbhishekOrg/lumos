/*
 * Lumos - android-app/app / The onboarding state machine and the surface gate.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-IDN-003
 * ADRs: -
 * Threading: serial-executor
 * Notes: An explicit State machine with typed events (TRS Sec. 24.2). It holds
 * no data beyond its state, touches no storage and calls nothing - which is why
 * every transition and every gate decision is a plain unit test with no fixture.
 *
 * The two rules worth stating out loud:
 *
 * 1. Unexpected events are IGNORED, not thrown. A user who taps twice, or a
 *    screen that redelivers an event after rotation, must not crash onboarding
 *    or - far worse - advance it. Ignoring is the only response that is safe in
 *    both directions.
 * 2. isReachable() answers for a CLOSED set with a closed default. It asks the
 *    surface whether it is part of onboarding; it does not consult a list of
 *    blocked screens. A list of blocked screens is a list somebody forgets to
 *    add to (threat model LT-30).
 */

package com.lumos.app.onboarding;

/** Tracks where onboarding is, and what that makes reachable. */
public final class OnboardingMachine {

    private OnboardingState state;

    /** Starts a fresh machine at the age gate. */
    public OnboardingMachine() {
        this(OnboardingState.AGE_GATE);
    }

    /**
     * Resumes a machine at a persisted state.
     *
     * @param state the state read back from storage
     */
    public OnboardingMachine(final OnboardingState state) {
        if (state == null) {
            throw new IllegalArgumentException("onboarding state is required");
        }
        this.state = state;
    }

    /**
     * The current state.
     *
     * @return where onboarding is
     */
    public OnboardingState state() {
        return state;
    }

    /**
     * Applies an event, returning the state it produced.
     *
     * <p>An event that does not belong to the current state leaves the state
     * unchanged. See the class note: silently ignoring is safer than either
     * throwing or advancing.
     *
     * @param event the typed event
     * @return the state after the event
     */
    public OnboardingState on(final OnboardingEvent event) {
        if (event instanceof OnboardingEvent.RestartRequested) {
            state = OnboardingState.AGE_GATE;
            return state;
        }
        if (event instanceof OnboardingEvent.AgeDeclared declared
                && state == OnboardingState.AGE_GATE) {
            state = declared.decision().isAdult()
                    ? OnboardingState.LEGAL_CONSENT
                    : OnboardingState.BLOCKED_UNDER_AGE;
            return state;
        }
        if (event instanceof OnboardingEvent.LegalDocumentsAccepted
                && state == OnboardingState.LEGAL_CONSENT) {
            state = OnboardingState.IDENTITY_CREATION;
            return state;
        }
        if (event instanceof OnboardingEvent.IdentityCreated
                && state == OnboardingState.IDENTITY_CREATION) {
            state = OnboardingState.COMPLETE;
            return state;
        }
        return state;
    }

    /**
     * Whether a surface may be shown right now (SRS-ONB-001, threat model LT-30).
     *
     * <p>After onboarding completes, everything is reachable. Before it
     * completes, only the onboarding step the machine is actually on is - so a
     * user cannot skip ahead to the consent screen without answering the age
     * gate, and no application surface is reachable at all.
     *
     * @param surface the surface being asked for
     * @return true only when this surface may be shown in this state
     */
    public boolean isReachable(final AppSurface surface) {
        if (surface == null) {
            return false;
        }
        if (state == OnboardingState.COMPLETE) {
            return true;
        }
        if (!surface.isPartOfOnboarding()) {
            return false;
        }
        return surface == currentStep();
    }

    /**
     * The onboarding surface this state expects, or null when there is none.
     *
     * @return the step's surface, or null in COMPLETE and BLOCKED_UNDER_AGE
     */
    public AppSurface currentStep() {
        switch (state) {
            case AGE_GATE:
                return AppSurface.AGE_GATE;
            case LEGAL_CONSENT:
                return AppSurface.LEGAL_CONSENT;
            case IDENTITY_CREATION:
                return AppSurface.IDENTITY_CREATION;
            case COMPLETE:
            case BLOCKED_UNDER_AGE:
            default:
                return null;
        }
    }
}

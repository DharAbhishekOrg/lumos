/*
 * Lumos - android-app/app / Every user-reachable surface, and which gate it sits behind.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002
 * ADRs: -
 * Threading: any
 * Notes: Threat model LT-30 - "profile surface reachable before the age gate" -
 * is High severity and the WP-02 kickoff names it a trap. The trap is not that
 * someone forgets to gate the profile editor; it is that someone adds the NEXT
 * surface a year from now and forgets. So the default here is closed: a surface
 * is reachable before onboarding completes only if it explicitly says it is
 * part of onboarding, and OnboardingGateTest walks every enum value rather than
 * a list of names. A new surface added without a thought is a blocked surface.
 *
 * Surfaces that do not exist yet are listed anyway, with the work package that
 * builds them. Listing them costs nothing and means the gate is already right
 * when they arrive; omitting them would mean the gate silently has a hole the
 * day the screen lands.
 */

package com.lumos.app.onboarding;

/** The surfaces of the application, and whether each is part of onboarding. */
public enum AppSurface {

    /** The adult-only age gate itself (SRS-ONB-001). */
    AGE_GATE(true),

    /** Terms, Privacy Notice, Community Rules and the radio disclosure (SRS-ONB-002). */
    LEGAL_CONSENT(true),

    /** Creating the offline, no-account local identity (SRS-ONB-006). */
    IDENTITY_CREATION(true),

    /** Creating or editing the profile (SRS-PRO-001). */
    PROFILE_EDITOR(false),

    /** Nearby discovery and the cards it renders (WP-03). */
    NEARBY_DISCOVERY(false),

    /** Interest, match and conversation surfaces (WP-04, WP-05). */
    CONVERSATIONS(false),

    /** Venue rooms and venue content (WP-07). */
    VENUE_ROOMS(false),

    /** Settings, including privacy and visibility controls. */
    SETTINGS(false),

    /** Blocking, reporting and the safety tools (WP-04). */
    SAFETY_CENTRE(false),

    /** Recovery setup and restore (SRS-ONB-007). */
    RECOVERY(false),

    /** Emergency wipe (SRS-SAF-009). */
    EMERGENCY_WIPE(false);

    private final boolean partOfOnboarding;

    AppSurface(final boolean partOfOnboarding) {
        this.partOfOnboarding = partOfOnboarding;
    }

    /**
     * Whether this surface is one of the onboarding steps themselves.
     *
     * <p>Only these are reachable before onboarding completes, and only in the
     * order {@link OnboardingMachine} allows. Everything else is closed.
     *
     * @return true for the onboarding steps
     */
    public boolean isPartOfOnboarding() {
        return partOfOnboarding;
    }
}

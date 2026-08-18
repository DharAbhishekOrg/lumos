/*
 * Lumos - android-app/app / Offline, no-account onboarding end to end.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008
 * ADRs: ADR-008, ADR-010
 * Threading: serial-executor
 * Notes: The whole of onboarding, and the whole of it is offline. Nothing here
 * opens a socket, asks for a phone number, an email address or a login from
 * another service - threat model LT-34 - and OnboardingServiceTest asserts the
 * class imports nothing that could. The identity is generated on the device by
 * IdentityKeyGenerator and exists nowhere else.
 *
 * The order is normative and it is the order of the SRS: age gate, then the
 * documents, then the identity. Generating keys before the gate would create
 * cryptographic material for someone who is about to be refused, and refusing
 * them afterwards would leave it behind.
 *
 * Visible Nearby is not touched. Completing onboarding does not enable it, and
 * there is no line here that could - SRS-ONB-008, threat model LT-33. The
 * absence is the implementation.
 */

package com.lumos.app.onboarding;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.Map;

/** Runs onboarding: the gate, the documents, and the local identity. */
public final class OnboardingService {

    private final OnboardingMachine machine;
    private final ConsentRepository consent;
    private final IdentityRepository identities;
    private final KekStore kek;
    private final LocalStore.Clock clock;

    /**
     * Assembles the service from its collaborators (constructor injection,
     * TRS Sec. 24.2).
     *
     * @param machine the state machine, fresh or resumed
     * @param consent where consent evidence is stored
     * @param identities where the device identity is stored
     * @param kek the key-encryption key the identity envelope is wrapped under
     * @param clock the UTC clock
     */
    public OnboardingService(final OnboardingMachine machine, final ConsentRepository consent,
            final IdentityRepository identities, final KekStore kek, final LocalStore.Clock clock) {
        this.machine = machine;
        this.consent = consent;
        this.identities = identities;
        this.kek = kek;
        this.clock = clock;
    }

    /**
     * The state machine, for the surface gate to ask.
     *
     * @return the machine
     */
    public OnboardingMachine machine() {
        return machine;
    }

    /**
     * Step one: judge a declared date of birth (SRS-ONB-001).
     *
     * <p>The date is passed straight to {@link AgeGate} and is not retained by
     * anything this method touches.
     *
     * @param dateOfBirth what the user declared
     * @param today the current date
     * @return the state after the gate: LEGAL_CONSENT or BLOCKED_UNDER_AGE
     */
    public OnboardingState declareAge(final LocalDate dateOfBirth, final LocalDate today) {
        return machine.on(new OnboardingEvent.AgeDeclared(AgeGate.evaluate(dateOfBirth, today)));
    }

    /**
     * Step two: record acceptance of the documents that were shown (SRS-ONB-002).
     *
     * <p>The versions come from the loaded documents rather than from the
     * caller, so what is recorded is what the user was actually shown. The
     * consent record is written before the state advances: a crash between the
     * two re-asks, which is the harmless direction.
     *
     * @param shown the documents that were displayed, with their versions
     * @return the state after acceptance
     * @throws StoreException on low storage or engine failure
     */
    public OnboardingState acceptDocuments(final LegalDocuments shown) throws StoreException {
        final Map<LegalDocument, String> versions = shown.versions();
        consent.save(new ConsentRecord(clock.nowUtcMillis(), AgeGate.minimumAgeYears(), versions));
        return machine.on(new OnboardingEvent.LegalDocumentsAccepted(versions));
    }

    /**
     * Step three: create the local identity, offline (SRS-ONB-006).
     *
     * <p>No account, no phone number, no email address, no social login. The
     * data-key hierarchy is created in the same step because both are wrapped
     * under the same KEK and a device with an identity but no data root is a
     * state nothing else in the application knows how to be in.
     *
     * @return the wrapped data root, for the caller to persist alongside
     * @throws GeneralSecurityException if key generation or wrapping fails
     * @throws StoreException on low storage or engine failure
     * @throws IllegalStateException if called before the documents are accepted
     */
    public byte[] createIdentity() throws GeneralSecurityException, StoreException {
        if (machine.state() != OnboardingState.IDENTITY_CREATION) {
            throw new IllegalStateException(
                    "identity creation runs after the gate and the documents, not before");
        }
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(kek);
        identities.replace(record);
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        machine.on(new OnboardingEvent.IdentityCreated());
        return hierarchy.wrappedRoot();
    }
}

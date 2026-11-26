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
 * The store's key comes from the data-key hierarchy, and the hierarchy is
 * made with the identity - so there is no store to write consent into before
 * the last step. The accepted consent is held in memory and written in one
 * transaction with the identity; the key root file (ADR-012) is written first,
 * so a boot that finds a root but no identity simply onboards again. This is
 * the iOS order (CS3b) and replaces the earlier test-only shape that took an
 * already open store: no key exists for someone the gate refuses, and there is
 * never an identity without its consent.
 *
 * Visible Nearby is not touched. Completing onboarding does not enable it, and
 * there is no line here that could - SRS-ONB-008, threat model LT-33. The
 * absence is the implementation.
 */

package com.lumos.app.onboarding;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.identity.IdentityCrypto;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.Map;

/** Runs onboarding: the gate, the documents, and the local identity. */
public final class OnboardingService {

    /** Where the last step puts the install's keys and store. */
    public interface Installation {

        /**
         * Creates the key-encryption key (the Keystore on a device).
         *
         * @return the new KEK
         * @throws GeneralSecurityException if the platform refuses
         */
        KekStore createKek() throws GeneralSecurityException;

        /**
         * Writes the key root file (ADR-012).
         *
         * @param root the protection tier and the wrapped root
         * @throws IOException if the file cannot be written
         */
        void persistRoot(KeyRootFile.Root root) throws IOException;

        /**
         * Opens the store under the new hierarchy.
         *
         * @param keys the data-key hierarchy
         * @return the opened store
         * @throws StoreException if the store cannot open
         * @throws GeneralSecurityException if the key cannot be derived
         */
        LocalStore openStore(DataKeyHierarchy keys) throws StoreException, GeneralSecurityException;
    }

    private final OnboardingMachine machine;
    private final IdentityCrypto crypto;
    private final LocalStore.Clock clock;
    private ConsentRecord accepted;

    /**
     * @param machine the state machine, fresh or resumed
     * @param crypto the identity key primitives
     * @param clock the UTC clock
     */
    public OnboardingService(final OnboardingMachine machine, final IdentityCrypto crypto,
            final LocalStore.Clock clock) {
        this.machine = machine;
        this.crypto = crypto;
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
     * Step one: judge a declared date of birth (SRS-ONB-001). Nothing keeps it.
     *
     * @param dateOfBirth the declared date
     * @param today the device's today
     * @return the state after the gate
     */
    public OnboardingState declareAge(final LocalDate dateOfBirth, final LocalDate today) {
        return machine.on(new OnboardingEvent.AgeDeclared(AgeGate.evaluate(dateOfBirth, today)));
    }

    /**
     * Step two: accept the documents that were shown (SRS-ONB-002). The
     * versions come from the documents themselves, so what is recorded is what
     * was displayed. Held until the identity is created.
     *
     * @param shown the documents as loaded from the app's assets
     * @return the state after acceptance
     */
    public OnboardingState acceptDocuments(final LegalDocuments shown) {
        final OnboardingState before = machine.state();
        final Map<LegalDocument, String> versions = shown.versions();
        final OnboardingState after = machine.on(new OnboardingEvent.LegalDocumentsAccepted(versions));
        if (before == OnboardingState.LEGAL_CONSENT && after == OnboardingState.IDENTITY_CREATION) {
            accepted = new ConsentRecord(clock.nowUtcMillis(), AgeGate.minimumAgeYears(), versions);
        }
        return after;
    }

    /**
     * Step three, offline (SRS-ONB-006): the KEK, the key root, the store, the
     * identity, and the held consent with it in one transaction.
     *
     * @param install where the keys and the store go
     * @return the store, open and holding the identity and the consent
     * @throws IllegalStateException before the gate and the documents
     * @throws GeneralSecurityException on a key failure; the state is unchanged
     * @throws StoreException on a store failure; the state is unchanged
     * @throws IOException if the key root cannot be written; the state is unchanged
     */
    public LocalStore createIdentity(final Installation install)
            throws GeneralSecurityException, StoreException, IOException {
        final ConsentRecord consent = accepted;
        if (machine.state() != OnboardingState.IDENTITY_CREATION || consent == null) {
            throw new IllegalStateException(
                    "identity creation runs after the gate and the documents, not before");
        }
        final KekStore kek = install.createKek();
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(kek);
        final IdentityKeyRecord record = IdentityKeyGenerator.generate(crypto, kek);
        install.persistRoot(new KeyRootFile.Root(kek.protection(), hierarchy.wrappedRoot()));
        final LocalStore store = install.openStore(hierarchy);
        store.transaction(() -> {
            new ConsentRepository(store).save(consent);
            new IdentityRepository(store).replace(record);
            return null;
        });
        accepted = null;
        machine.on(new OnboardingEvent.IdentityCreated());
        return store;
    }
}

/// Lumos - LumosAppShell / Offline, no-account onboarding end to end.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008
/// ADRs: ADR-008, ADR-010
/// Threading: serial-executor
/// Notes: The Swift side of the Android OnboardingService, in the SRS's order:
/// the age gate, then the documents, then the identity. Nothing here opens a
/// socket or asks for a phone number, an email address or another service's
/// login (threat model LT-34); the identity is generated on the device.
///
/// One difference from Android, forced by where the keys live. On iOS the
/// encrypted store's key comes from the key root, and the key root is created
/// with the identity - so there is no store to write consent into before the
/// last step. The accepted consent is therefore held in memory and written in
/// the same transaction as the identity. That keeps both rules: no key exists
/// for someone the gate refuses, and a crash before the last step leaves
/// nothing behind and simply asks again, which is the harmless direction.
///
/// Visible Nearby is not touched. Completing onboarding does not enable it,
/// and there is no line here that could (SRS-ONB-008, threat model LT-33).

import Foundation

/// Runs onboarding: the gate, the documents, and the local identity.
public final class OnboardingService {

    /// Identity creation was asked for before the gate and the documents.
    public struct OutOfOrder: Error, Equatable, Sendable {}

    /// The state machine, for the surface gate to ask.
    public let machine: OnboardingMachine
    private let clock: () -> Int64
    private var accepted: ConsentRecord?

    /// - Parameters:
    ///   - machine: the state machine, fresh or resumed
    ///   - clock: the UTC clock, in milliseconds
    public init(machine: OnboardingMachine = OnboardingMachine(), clock: @escaping () -> Int64) {
        self.machine = machine
        self.clock = clock
    }

    /// Step one: judge a declared date of birth (SRS-ONB-001). The date is
    /// handed to the gate and kept by nothing here.
    /// - Returns: the state after the gate: legal consent, or blocked under age
    @discardableResult
    public func declareAge(dateOfBirth: Date, today: Date) -> OnboardingState {
        machine.on(.ageDeclared(AgeGate.evaluate(dateOfBirth: dateOfBirth, today: today)))
    }

    /// Step two: accept the documents that were shown (SRS-ONB-002). The
    /// versions come from the documents themselves, so what is recorded is
    /// what was displayed. Held until the identity is created (see Notes).
    /// - Returns: the state after acceptance
    @discardableResult
    public func acceptDocuments(_ shown: LegalDocuments) -> OnboardingState {
        let before = machine.state
        let after = machine.on(.legalDocumentsAccepted(shown.versions))
        if before == .legalConsent && after == .identityCreation {
            accepted = ConsentRecord(affirmedAtUtcMillis: clock(), minimumAgeYears: AgeGate.minimumAgeYears,
                                     acceptedVersions: shown.versions)
        }
        return after
    }

    /// Step three: create the key root, the store and the local identity,
    /// offline (SRS-ONB-006), and write the held consent with the identity in
    /// one transaction.
    /// - Parameters:
    ///   - vault: where the key root is kept (the Keychain on a device)
    ///   - preferHardware: use the Secure Enclave when the device has one
    ///   - openStore: opens the encrypted store under the new root
    /// - Returns: the store, open and holding the identity and the consent
    /// - Throws: ``OutOfOrder`` before the gate and the documents; otherwise
    ///   key, Keychain or store errors, after which the state is unchanged
    public func createIdentity(vault: KeyRootVault, preferHardware: Bool = true,
                               openStore: (KeyRoot) throws -> LocalStore) throws -> LocalStore {
        guard machine.state == .identityCreation, let consent = accepted else { throw OutOfOrder() }
        let root = try KeyRoot.create(in: vault, preferHardware: preferHardware)
        let store = try openStore(root)
        let identity = try IdentityKeyGenerator.generate(kek: root.kek)
        try store.transaction {
            try ConsentRepository(store: store).save(consent)
            try IdentityRepository(store: store).replace(identity)
        }
        accepted = nil
        machine.on(.identityCreated)
        return store
    }
}

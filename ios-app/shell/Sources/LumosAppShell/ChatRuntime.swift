/// Lumos - LumosAppShell / The unlocked chat identity, the relay schedule, and the chat runtime assembled once.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-018, TRS-TRN-012, SRS-NFR-BAT-001, TRS-PAT-004
/// ADRs: ADR-001, ADR-003, ADR-010, ADR-012
/// Threading: serial-executor
/// Notes: The Swift port of the Android ChatIdentity, RelaySchedule and
/// ChatRuntime.
///
/// ChatIdentity: the identity's private halves live wrapped under the KEK
/// (ADR-003). The chat needs the X25519 key for X3DH and the ratchet, and the
/// Ed25519 key to sign the prekeys it publishes. ADR-010 rules out key-level
/// authentication gating because background decryption needs them, so they
/// are unwrapped once for the runtime's lifetime. CryptoKit offers no
/// zeroisation hook for its key types; the runtime holds the only references
/// and close() drops them.
///
/// RelaySchedule: offline, nothing retries on a timer; the next pass waits for
/// the platform to report a network (SRS-RMT-003, SRS-NFR-BAT-001). Rate
/// limited: the server's Retry-After. Busy without one: the platform's
/// backoff. A refused credential or more waiting: run again at once, never
/// more than maxPassesPerJob times, so a bad server cannot pin the CPU.
///
/// ChatRuntime: one chat store, one set of routes, one registration, one
/// prekey publisher, one relay pass and one route exchange, over the same
/// sealed LocalStore and the same unlocked identity, all on one serial queue.

import CryptoKit
import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// The identity, unlocked for the chat.
public final class ChatIdentity {
    /// The chat's view of the identity.
    public let identity: RemoteSession.Identity
    private var signing: Curve25519.Signing.PrivateKey?

    private init(identity: RemoteSession.Identity, signing: Curve25519.Signing.PrivateKey) {
        self.identity = identity
        self.signing = signing
    }

    /// Unwraps the identity's private halves under the KEK.
    /// - Throws: ``KekError`` when the KEK cannot unwrap; ``IdentityError`` on a malformed envelope
    public static func unlock(record: IdentityKeyRecord, kek: KekStore) throws -> ChatIdentity {
        let envelope = try kek.unwrap(record.wrappedPrivateKeys, aad: Data(IdentityKeyGenerator.envelopeAad.utf8))
        let keys = try IdentityKeyGenerator.decodeEnvelope(envelope)
        guard let signing = try? Curve25519.Signing.PrivateKey(rawRepresentation: keys.ed25519Raw),
              signing.publicKey.rawRepresentation == record.ed25519Public,
              let agreement = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: keys.x25519Raw),
              agreement.publicKey.rawRepresentation == record.x25519Public else {
            throw IdentityError.envelopeMalformed
        }
        return ChatIdentity(identity: RemoteSession.Identity(identityEd: record.ed25519Public,
                                                             identityDHPriv: keys.x25519Raw,
                                                             identityDHSig: record.bindingSignature),
                            signing: signing)
    }

    /// Signs with the identity key (prekey bundles).
    /// - Throws: ``KekError/destroyed`` after close()
    public func sign(_ message: Data) throws -> Data {
        guard let signing else { throw KekError.destroyed }
        return try signing.signature(for: message)
    }

    /// Drops the signing key.
    public func close() {
        signing = nil
    }
}

/// What a background relay job does after each pass.
public enum RelaySchedule {

    /// The most passes one job runs.
    public static let maxPassesPerJob = 8

    /// The next step.
    public enum Next: Sendable, Equatable {
        /// Run another pass now.
        case again
        /// Nothing more to do.
        case done
        /// Wait for the platform to report a network.
        case whenOnline
        /// Wait the server's Retry-After.
        case afterDelay(millis: Int64)
        /// Let the platform back off.
        case backoff
    }

    /// The next step after a pass.
    public static func after(_ r: RelaySync.Report, passesSoFar: Int) -> Next {
        let canLoop = passesSoFar < maxPassesPerJob
        guard let stop = r.stoppedBy else { return r.moreWaiting && canLoop ? .again : .done }
        switch stop.kind {
        case .offline: return .whenOnline
        case .auth: return canLoop ? .again : .backoff
        case .busy: return stop.retryAfterSeconds >= 0 ? .afterDelay(millis: stop.retryAfterSeconds * 1000) : .backoff
        default: return .backoff
        }
    }
}

/// The assembled chat runtime.
public final class ChatRuntime {
    /// The one chat store, shared with the UI on purpose.
    public let chat: ChatStore
    /// The relay pass.
    public let sync: RelaySync
    /// The RELAY_ROUTE exchange, for the nearby sessions.
    public let exchange: RouteExchange
    private let sessions: RelaySessions

    private init(chat: ChatStore, sync: RelaySync, exchange: RouteExchange, sessions: RelaySessions) {
        self.chat = chat
        self.sync = sync
        self.exchange = exchange
        self.sessions = sessions
    }

    /// Assembles the runtime.
    /// - Parameters:
    ///   - store: the sealed local store
    ///   - identity: the unlocked identity
    ///   - transport: the relay transport
    ///   - clock: the UTC-millisecond clock
    ///   - random: the CSPRNG
    public static func assemble(store: LocalStore, identity: ChatIdentity, transport: HttpTransport,
                                clock: @escaping () -> Int64,
                                random: @escaping RandomBytes = secureRandomBytes) -> ChatRuntime {
        let chat = ChatStore(store: store, own: identity.identity, clock: clock, ratchetKeys: { random(32) })
        let routes = RelayRoutes(store: store, random: random)
        let account = RelayAccount(store: store)
        let prekeys = PrekeyPublisher(store: store, chat: chat, own: identity.identity,
                                      signer: { try identity.sign($0) }, random: random)
        let anonymous = RelayClient.anonymous(transport)
        return ChatRuntime(chat: chat,
                           sync: RelaySync(chat: chat, routes: routes, account: account, prekeys: prekeys,
                                           anonymous: anonymous, clock: clock),
                           exchange: RouteExchange(routes: routes, account: account),
                           sessions: RelaySessions(chat: chat, routes: routes, account: account, anonymous: anonymous,
                                                   random: random))
    }

    /// Queues a text event, starting the session first when there is none.
    /// - Returns: the new message id, or nil while no session can start yet (no route or no bundle)
    public func send(matchID: Data, peerIdentityEd: Data, event: RemoteText.TextEvent) throws -> Data? {
        guard try sessions.start(matchID: matchID, peerIdentityEd: peerIdentityEd) else { return nil }
        return try chat.send(matchID: matchID, event: event)
    }
}

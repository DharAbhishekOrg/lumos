/// Lumos - ios-app/shell / This install's own HELLO, from its stored identity.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-017, SRS-IDN-002, SRS-NBY-005
/// ADRs: ADR-002, ADR-008, ADR-013
/// Threading: any
/// Notes: The Swift twin of the Android NearbyIdentity: every link gets a
/// fresh HelloExchange over the install's real keys - the Ed25519 identity
/// key, the X25519 identity-DH key and its ADR-008 binding signature, all from
/// the stored IdentityKeyRecord - signed through the unlocked identity. The
/// EID is all zeros (this phone advertises UUID-only: "zeros when hintless"),
/// the venue hint 0 and the capability bits 0 until those features exist;
/// the nonce is set per link by HelloExchange.start.

import Foundation
import LumosProtocol
import LumosProtocolGenerated

/// This install as discovery's identity.
public struct NearbyIdentity {

    /// No capability claims until the profile composes them.
    static let noCapabilities = 0
    /// No venue context before the venue slices.
    static let noVenue: Int64 = 0

    private let own: Hello.Fields
    private let signer: (Data) throws -> Data

    /// - Parameters:
    ///   - record: the stored identity (public halves and the binding signature)
    ///   - signer: signs with the identity's Ed25519 key
    public init(record: IdentityKeyRecord, signer: @escaping (Data) throws -> Data) {
        self.own = Hello.Fields(protocolVersion: LumosConstants.protocolVersionEncoded,
                                capabilityBits: Self.noCapabilities, venueHint: Self.noVenue,
                                eid: Data(count: Hello.eidLen), ed25519Public: record.ed25519Public,
                                x25519Public: record.x25519Public, bindingSignature: record.bindingSignature,
                                nonce: Data(count: Hello.nonceLen))
        self.signer = signer
    }

    /// - Returns: a fresh HELLO exchange for one link
    public func freshExchange() -> HelloExchange {
        HelloExchange(signer: signer, own: own)
    }
}

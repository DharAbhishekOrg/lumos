/// Lumos - protocol-conformance / BLE advertisement hint: EID schedule and authTag.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-009, TRS-PROTO-017, TRS-PROTO-018, SRS-IDN-004
/// ADRs: ADR-002, ADR-004
/// Threading: any
/// Notes: The Swift port of protocol/adv, held to test-vectors/adv/
/// ble_advertisement.json beside the Go reference and the Java port. An iOS
/// device never ADVERTISES this hint (ADR-002: iOS advertises the service UUID
/// alone), but an iOS CENTRAL sees Android hints in scan results, so parsing
/// and the schedule live here for the discovery triage - and build() exists so
/// the synthetic venue tests can manufacture hinted peers through the same
/// vector-pinned code Android radios use. THIS PAYLOAD CARRIES NO TRUST: a
/// structurally valid or even tag-verified hint is "worth connecting to",
/// never an authenticated peer (TRS-PROTO-017). Epoch arithmetic is pure; the
/// caller supplies unix seconds.

import Foundation
import LumosProtocolGenerated

/// The adv_v1 Android Service-Data hint (TRS Sec. 9.3, ADR-002).
public enum Adv {

    /// Total adv_v1 hint length.
    public static let totalLen = LumosConstants.advV1LayoutTotalLen

    /// Rotating EID length.
    public static let eidLen = LumosConstants.advV1LayoutEIDLen

    /// Advertisement auth tag length.
    public static let authTagLen = LumosConstants.advAuthTagLenDefault

    /// Android scan response length.
    public static let scanResponseLen = LumosConstants.advV1ScanResponseLayoutTotalLen

    /// mode_flags bit 0: venue mode.
    public static let modeFlagVenue = 0x01

    /// mode_flags bits 1..7: reserved, must be zero.
    public static let modeFlagReservedMask = 0xFE

    /// A decoded adv_v1 hint. Structure, never trust.
    public struct Payload: Equatable, Sendable {
        /// The advertisement version byte.
        public let version: Int
        /// The mode flags byte.
        public let modeFlags: Int
        /// The 12-byte rotating EID.
        public let eid: Data
        /// The 4-byte auth tag.
        public let authTag: Data

        /// Whether the venue mode flag is set.
        public var venue: Bool { modeFlags & Adv.modeFlagVenue != 0 }
    }

    /// Returns the EID rotation epoch for a unix timestamp (SRS-IDN-004).
    /// - Parameters:
    ///   - unixSeconds: the caller's clock
    ///   - rotationSec: rotation cadence; non-positive falls back to the
    ///     register default so a broken override cannot freeze the schedule
    /// - Returns: the epoch index
    public static func epoch(unixSeconds: Int64, rotationSec: Int) -> Int64 {
        let cadence = rotationSec <= 0 ? LumosConstants.eidRotationSecDefault : rotationSec
        return unixSeconds / Int64(cadence)
    }

    /// Derives the per-identity advertisement key. K_adv never leaves the device.
    /// - Parameter advSecret: the device's long-term advertisement secret
    /// - Returns: the 32-byte K_adv
    public static func deriveKAdv(advSecret: Data) -> Data {
        LumosCrypto.hkdf(ikm: advSecret, salt: Data(count: 32),
                         info: Data(LumosConstants.advKeyKdfInfoValue.utf8), length: 32)
    }

    /// Derives the rotating ephemeral identifier for an epoch (SRS-IDN-004).
    /// - Parameters:
    ///   - kAdv: the advertisement key
    ///   - epoch: the rotation epoch
    /// - Returns: the 12-byte EID
    public static func deriveEid(kAdv: Data, epoch: Int64) -> Data {
        let info = Data(LumosConstants.advEIDKdfInfoValue.utf8)
            + LumosCrypto.be64(UInt64(bitPattern: epoch))
        return LumosCrypto.hkdf(ikm: kAdv, salt: Data(count: 32), info: info, length: eidLen)
    }

    /// Computes the advertisement tag: the first authTagLen bytes of
    /// HMAC-SHA-256(K_adv, info | version | mode_flags | eid | epoch).
    /// - Parameters:
    ///   - kAdv: the advertisement key
    ///   - version: the advertisement version byte
    ///   - modeFlags: the mode flags byte
    ///   - eid: the rotating EID
    ///   - epoch: the rotation epoch
    /// - Returns: the 4-byte tag
    public static func authTag(kAdv: Data, version: Int, modeFlags: Int,
                               eid: Data, epoch: Int64) -> Data {
        var message = Data(LumosConstants.advAuthTagInfoValue.utf8)
        message.append(UInt8(version & 0xFF))
        message.append(UInt8(modeFlags & 0xFF))
        message.append(eid)
        message.append(LumosCrypto.be64(UInt64(bitPattern: epoch)))
        return LumosCrypto.hmacSHA256(key: kAdv, data: message).prefix(authTagLen)
    }

    /// Assembles the 18-byte Android service-data hint for an epoch.
    /// - Parameters:
    ///   - kAdv: the advertisement key
    ///   - modeFlags: the mode flags byte; reserved bits must be clear
    ///   - epoch: the rotation epoch
    /// - Returns: the adv_v1 bytes
    /// - Throws: ``LumosCrypto/WireError`` when a reserved mode flag is set
    public static func build(kAdv: Data, modeFlags: Int, epoch: Int64) throws -> Data {
        guard modeFlags & modeFlagReservedMask == 0 else {
            throw LumosCrypto.WireError(LumosErrors.errAdvAuthTagMismatch, "reserved mode flag")
        }
        let eid = deriveEid(kAdv: kAdv, epoch: epoch)
        let version = 1
        var out = Data(capacity: totalLen)
        out.append(UInt8(version))
        out.append(UInt8(modeFlags & 0xFF))
        out.append(eid)
        out.append(authTag(kAdv: kAdv, version: version, modeFlags: modeFlags,
                           eid: eid, epoch: epoch))
        return out
    }

    /// Decodes an adv_v1 payload without verifying it. Structure only: this is
    /// the whole of the cheap discard a scanner can apply to a stranger's
    /// hint, and even a verified hint is never identity (TRS-PROTO-017).
    /// - Parameter data: the service data bytes
    /// - Returns: the decoded payload
    /// - Throws: ``LumosCrypto/WireError`` when the structure is not adv_v1
    public static func parse(_ data: Data) throws -> Payload {
        guard data.count == totalLen else {
            throw LumosCrypto.WireError(LumosErrors.errAdvAuthTagMismatch, "advertisement length")
        }
        let bytes = [UInt8](data)
        let version = Int(bytes[0])
        let modeFlags = Int(bytes[1])
        guard version == 1 else {
            throw LumosCrypto.WireError(LumosErrors.errAdvAuthTagMismatch, "advertisement version")
        }
        guard modeFlags & modeFlagReservedMask == 0 else {
            throw LumosCrypto.WireError(LumosErrors.errAdvAuthTagMismatch, "reserved mode flag")
        }
        return Payload(version: version, modeFlags: modeFlags,
                       eid: Data(bytes[2..<(2 + eidLen)]),
                       authTag: Data(bytes[(2 + eidLen)..<totalLen]))
    }

    /// Recomputes the tag for an epoch and compares in constant time. A
    /// mismatch is discarded silently (ADR-002); a match still carries no
    /// trust.
    /// - Parameters:
    ///   - kAdv: the advertisement key the tag is expected under
    ///   - data: the service data bytes
    ///   - epoch: the epoch to verify against
    /// - Returns: the verified payload
    /// - Throws: ``LumosCrypto/WireError`` when the structure or tag is wrong
    public static func verify(kAdv: Data, _ data: Data, epoch: Int64) throws -> Payload {
        let payload = try parse(data)
        let want = authTag(kAdv: kAdv, version: payload.version,
                           modeFlags: payload.modeFlags, eid: payload.eid, epoch: epoch)
        guard constantTimeEqual(want, payload.authTag) else {
            throw LumosCrypto.WireError(LumosErrors.errAdvAuthTagMismatch, "advertisement discarded")
        }
        return payload
    }

    /// Assembles the 6-byte Android scan response: capabilityBits(2) |
    /// venueHint(4).
    /// - Parameters:
    ///   - capabilityBits: the capability bits
    ///   - venueHint: the venue hint
    /// - Returns: the scan response bytes
    public static func buildScanResponse(capabilityBits: Int, venueHint: Int64) -> Data {
        var out = Data(capacity: scanResponseLen)
        out.append(UInt8((capabilityBits >> 8) & 0xFF))
        out.append(UInt8(capabilityBits & 0xFF))
        out.append(UInt8((venueHint >> 24) & 0xFF))
        out.append(UInt8((venueHint >> 16) & 0xFF))
        out.append(UInt8((venueHint >> 8) & 0xFF))
        out.append(UInt8(venueHint & 0xFF))
        return out
    }

    /// Constant-time comparison for the short tag.
    private static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }
}

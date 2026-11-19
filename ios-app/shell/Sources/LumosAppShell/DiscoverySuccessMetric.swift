/// Lumos - ios-app/shell / The privacy-safe discovery-success metric.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-ENV-003, SRS-NFR-SEC-013, SRS-DAT-002
/// ADRs: ADR-002
/// Threading: serial-queue
/// Notes: The Android formatter, byte for byte: a closed grammar - version
/// line, two small counters, a peer BUCKET and a boolean - and nothing else
/// can get in because this formatter is the only writer. No peer identifier,
/// no EID, no RSSI, no venue, no clock reading; the bucket deliberately
/// destroys the venue-size fingerprint a raw count would carry. The record
/// lands in the sealed diagnostic_event table; nothing reads it today, and
/// any future upload sits behind its own consent gate (SRS-DAT-004).

import Foundation

/// Writes one privacy-safe record per Nearby viewing session.
public final class DiscoverySuccessMetric {

    /// Payload format version line.
    public static let versionLine = "lumos-discovery-metric/1"

    private let store: LocalStore

    /// Binds the metric to its store.
    public init(store: LocalStore) {
        self.store = store
    }

    /// The coarse peer-count bucket - coarse on purpose, see the header.
    public static func bucket(peersPresent: Int) -> String {
        switch peersPresent {
        case ..<1: return "none"
        case 1: return "one"
        case 2...5: return "few"
        case 6...20: return "some"
        default: return "many"
        }
    }

    /// Records one finished Nearby viewing session.
    /// - Parameters:
    ///   - cyclesRun: scan cycles completed inside the TRS-ENV-003 gate
    ///   - maxStepReached: the highest rescan-ladder step the session hit
    ///   - peersPresent: authenticated peers present when the view closed
    /// - Returns: the row identifier written
    /// - Throws: ``StoreError`` on low storage or engine failure
    @discardableResult
    public func record(cyclesRun: Int, maxStepReached: Int, peersPresent: Int) throws -> String {
        let payload = Self.versionLine + "\n"
            + "cycles=\(max(0, cyclesRun))\n"
            + "max_step=\(max(0, maxStepReached))\n"
            + "peers=\(Self.bucket(peersPresent: peersPresent))\n"
            + "success=\(peersPresent > 0)\n"
        return try store.put(kind: .diagnosticEvent,
                             plaintext: Data(payload.utf8))
    }
}

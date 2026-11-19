/// Lumos - LumosProtocolConformance / The shared Adv port against the adv vectors.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-009, TRS-PROTO-017, TRS-TST-001, TRS-PROTO-015
/// ADRs: ADR-002, ADR-004
/// Threading: any
/// Notes: CryptoConformanceTests recomputes the schedule from raw primitives
/// and stays as the independent check of the KDF chain. This test holds the
/// SHARED Adv type - the one the Apple discovery triage actually calls
/// (WP-03 S3) - to the same committed file: build reproduces every scheduled
/// payload byte for byte, parse/verify accept them, every negative is
/// rejected with the stable code, and the scan response matches. The same
/// test the Java port answers to, in the same shape.

import XCTest
import Foundation
@testable import LumosProtocol

final class AdvConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/adv/ble_advertisement.json"

    func testScheduleReproducesThroughTheSharedType() throws {
        let file = try Vectors.load(path, root: root)
        let kAdv = try Vectors.hex(file, "k_adv_hex")
        let rotation = try Vectors.int(file, "eid_rotation_sec")
        let schedule = try Vectors.array(file, "schedule")
        XCTAssertFalse(schedule.isEmpty, "an empty schedule proves nothing")

        for c in schedule {
            let epoch = Int64(try Vectors.int(c, "epoch"))
            XCTAssertEqual(epoch,
                           Adv.epoch(unixSeconds: Int64(try Vectors.int(c, "unix_seconds")),
                                     rotationSec: rotation),
                           "epoch arithmetic")

            XCTAssertEqual(try Vectors.hex(c, "eid_hex"),
                           Adv.deriveEid(kAdv: kAdv, epoch: epoch), "eid")
            XCTAssertEqual(try Vectors.string(c, "adv_v1_hex"),
                           Hex.encode(try Adv.build(kAdv: kAdv,
                                                    modeFlags: try Vectors.int(c, "mode_flags"),
                                                    epoch: epoch)),
                           "adv_v1 must build to the committed bytes")

            let verified = try Adv.verify(kAdv: kAdv, try Vectors.hex(c, "adv_v1_hex"),
                                          epoch: epoch)
            XCTAssertEqual(try Vectors.hex(c, "eid_hex"), verified.eid, "verified eid")
            XCTAssertEqual(try Vectors.hex(c, "auth_tag_hex"), verified.authTag, "verified tag")
            XCTAssertEqual(try Vectors.string(c, "scan_response_hex"),
                           Hex.encode(Adv.buildScanResponse(
                                capabilityBits: try Vectors.int(file, "capability_bits"),
                                venueHint: Int64(try Vectors.int(file, "venue_hint")))),
                           "scan response")
        }
    }

    func testEveryNegativeIsRejectedWithTheStableCode() throws {
        let file = try Vectors.load(path, root: root)
        let kAdv = try Vectors.hex(file, "k_adv_hex")
        let negatives = try Vectors.array(file, "negatives")
        XCTAssertFalse(negatives.isEmpty, "a family without negatives proves nothing")

        for c in negatives {
            let name = try Vectors.string(c, "name")
            do {
                _ = try Adv.verify(kAdv: kAdv, try Vectors.hex(c, "adv_v1_hex"),
                                   epoch: Int64(try Vectors.int(c, "epoch")))
                XCTFail("\(name): a hint that must be discarded verified")
            } catch let error as LumosCrypto.WireError {
                XCTAssertEqual(try Vectors.string(c, "expected_error"), error.code,
                               "\(name): stable code")
            }
        }
    }
}

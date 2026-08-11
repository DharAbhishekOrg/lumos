/// Lumos - LumosProtocolConformance / Framing and error-registry conformance.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001, TRS-PROTO-001, TRS-PROTO-006, TRS-PROTO-013, TRS-PROTO-015, SRS-NFR-CMP-004
/// ADRs: ADR-007
/// Threading: any
/// Notes: XCTest only - no UIKit, no simulator, no Xcode project - so this runs
/// under `swift test` on a macOS CI host exactly as TRS Sec. 18.4 requires.
/// Conformance is exact: positive vectors reproduce the committed bytes
/// byte-for-byte, negative vectors fail with the exact stable code.

import XCTest
import Foundation
@testable import LumosProtocol
import LumosProtocolGenerated

final class FramingConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }

    func testFrameRoundtripReproducesCommittedBytes() throws {
        let file = try Vectors.load("test-vectors/framing/frame_roundtrip.json", root: root)
        let cases = try Vectors.array(file, "cases")
        XCTAssertFalse(cases.isEmpty, "no framing cases")

        var covered = Set<UInt8>()
        var positives = 0

        for c in cases {
            let name = try Vectors.string(c, "name")
            guard let fields = c["fields"] as? [String: Any] else {
                XCTFail("case \(name) has no fields"); continue
            }
            let decodeOnly = Vectors.bool(c, "decode_only")

            let header = FrameHeader(
                version: UInt8(try Vectors.int(fields, "version")),
                type: UInt8(try Vectors.int(fields, "message_type")),
                flags: UInt16(try Vectors.int(fields, "flags")),
                sessionID: try Vectors.hex(fields, "session_id_hex"),
                messageID: try Vectors.hex(fields, "message_id_hex"),
                sequence: UInt32(try Vectors.int(fields, "sequence")),
                ackBase: UInt32(try Vectors.int(fields, "ack_base")),
                expirySeconds: UInt32(try Vectors.int(fields, "expiry_seconds")),
                hopLimit: UInt8(try Vectors.int(fields, "hop_limit"))
            )
            let payload = try Vectors.hex(fields, "payload_hex")
            let expectedHex = try Vectors.string(c, "frame_hex")

            if !decodeOnly {
                let encoded = try FrameCodec.encode(header: header, payload: payload)
                XCTAssertEqual(Hex.encode(encoded), expectedHex, "encoded bytes differ for \(name)")
                covered.insert(header.type)
                positives += 1
            }

            let decoded = try FrameCodec.decode(try Vectors.hex(c, "frame_hex"))
            XCTAssertEqual(decoded.header, header, "decoded header differs for \(name)")
            XCTAssertEqual(Hex.encode(decoded.payload), Hex.encode(payload), "payload for \(name)")
            XCTAssertEqual(decoded.payload.count, try Vectors.int(fields, "payload_length"),
                           "payload length for \(name)")

            if let expect = c["expect"] as? [String: Any] {
                XCTAssertEqual(decoded.header.messageClass != nil,
                               Vectors.bool(expect, "message_type_known"),
                               "message_type_known for \(name)")
            }
        }

        XCTAssertGreaterThan(positives, 0, "no positive framing cases")
        for mc in MessageClass.allCases {
            XCTAssertTrue(covered.contains(mc.rawValue),
                          "message class \(mc.canonicalName) has no positive roundtrip vector (TRS-PROTO-014)")
        }
    }

    func testFrameInvalidFailsWithExactCode() throws {
        let file = try Vectors.load("test-vectors/framing/frame_invalid.json", root: root)
        let cases = try Vectors.array(file, "cases")
        XCTAssertFalse(cases.isEmpty, "no negative framing cases")

        for c in cases {
            let name = try Vectors.string(c, "name")
            let expected = try Vectors.string(c, "expected_error")
            do {
                _ = try FrameCodec.decode(try Vectors.hex(c, "frame_hex"))
                XCTFail("decoder ACCEPTED negative vector \(name); must fail with \(expected)")
            } catch let e as LumosCrypto.WireError {
                XCTAssertEqual(e.code, expected, "wrong stable code for \(name)")
                XCTAssertTrue(LumosErrors.known(e.code), "\(e.code) is not in the stable registry")
            }
        }
    }

    func testErrorRegistryMatchesGenerated() throws {
        let file = try Vectors.load("test-vectors/errors/error_codes.json", root: root)
        let codes = try Vectors.array(file, "codes")
        XCTAssertFalse(codes.isEmpty, "empty error registry snapshot")

        for c in codes {
            let code = try Vectors.string(c, "code")
            XCTAssertTrue(LumosErrors.known(code), "\(code) missing from the generated Swift registry")
            XCTAssertEqual(LumosErrors.numeric[code], try Vectors.int(c, "numeric"),
                           "numeric drift for \(code)")
        }
        XCTAssertEqual(LumosErrors.numeric.count, codes.count,
                       "the generated registry and the committed snapshot differ in size")
    }

    func testAuthenticatedHeaderIsTheLeadingBytes() throws {
        let file = try Vectors.load("test-vectors/framing/frame_roundtrip.json", root: root)
        let first = try Vectors.array(file, "cases")[0]
        let encoded = try Vectors.hex(first, "frame_hex")
        let aad = try FrameCodec.headerBytes(encoded)
        XCTAssertEqual(aad.count, FrameCodec.headerLen)
        XCTAssertTrue(Hex.encode(encoded).hasPrefix(Hex.encode(aad)))
    }
}

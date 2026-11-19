/// Lumos - LumosProtocolConformance / Swift conformance to the fragmentation vectors.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-010, TRS-PROTO-015, TRS-TST-001
/// ADRs: ADR-004
/// Threading: any
/// Notes: The Swift quarter of the fragmentation family. split cases are exact:
/// Fragmentation.split must reproduce the committed fragment bytes, and the
/// committed fragments must reassemble to the committed blob. The scripted
/// timelines drive FragmentReassembler offer by offer with the file's own
/// millisecond clock, pinning reassembly timeout, duplicate suppression, gaps
/// and length mismatches to exact outcomes and exact stable codes - the same
/// contract the Go reference and the Java port answer to.

import XCTest
import Foundation
@testable import LumosProtocol

final class FragmentationConformanceTests: XCTestCase {

    private var root: URL { Vectors.repoRoot() }
    private let path = "test-vectors/framing/fragmentation.json"

    func testSplitVectorsReproduceByteForByte() throws {
        let file = try Vectors.load(path, root: root)
        let cases = try Vectors.array(file, "split")
        XCTAssertFalse(cases.isEmpty, "an empty split set proves nothing")

        for c in cases {
            let name = try Vectors.string(c, "name")
            let blob = try Vectors.hex(c, "blob_hex")
            let committed = try fragmentsHex(c)
            let fragments = try Fragmentation.split(
                blob,
                assemblyID: UInt8(try Vectors.int(c, "assembly_id")),
                chunkPayload: try Vectors.int(c, "chunk_payload"))
            XCTAssertEqual(committed.count, fragments.count, "\(name): fragment count")
            for (i, fragment) in fragments.enumerated() {
                XCTAssertEqual(committed[i], Hex.encode(fragment),
                               "\(name): fragment \(i) must match the committed bytes")
            }

            // The committed fragments must also reassemble to the committed blob.
            let reassembler = FragmentReassembler(timeoutMillis: 1)
            var out: Data?
            for hex in committed {
                guard let bytes = Hex.decode(hex) else {
                    return XCTFail("\(name): unparseable committed fragment")
                }
                let offer = try reassembler.offer(bytes, nowMillis: 0)
                if offer.outcome == .complete { out = offer.payload }
            }
            XCTAssertEqual(blob, out, "\(name): committed fragments must reassemble to the blob")
        }
    }

    func testScriptedTimelinesPlayOutExactly() throws {
        let file = try Vectors.load(path, root: root)
        let scripts = try Vectors.array(file, "scripts")
        XCTAssertFalse(scripts.isEmpty, "an empty script set proves nothing")

        for script in scripts {
            let name = try Vectors.string(script, "name")
            let reassembler = FragmentReassembler(
                timeoutMillis: Int64(try Vectors.int(script, "timeout_millis")))
            for (i, step) in (try Vectors.array(script, "steps")).enumerated() {
                try run(step: step, at: "\(name) step \(i)", on: reassembler)
            }
        }
    }

    private func run(step: [String: Any], at: String, on reassembler: FragmentReassembler) throws {
        let expect = try Vectors.string(step, "expect")
        let fragment = try Vectors.hex(step, "fragment_hex")
        let now = Int64(try Vectors.int(step, "at_millis"))
        do {
            let offer = try reassembler.offer(fragment, nowMillis: now)
            XCTAssertNotEqual("error", expect, "\(at): expected an error, offer succeeded")
            XCTAssertEqual(expect, outcomeName(offer.outcome), "\(at): outcome")
            XCTAssertEqual(Vectors.bool(step, "restarted"), offer.restarted, "\(at): restarted")
            XCTAssertEqual(Vectors.bool(step, "expired_previous"), offer.expiredPrevious,
                           "\(at): expired_previous")
            if expect == "complete" {
                XCTAssertEqual(try Vectors.string(step, "payload_hex"),
                               Hex.encode(offer.payload ?? Data()),
                               "\(at): reassembled payload")
            }
        } catch let error as LumosCrypto.WireError {
            XCTAssertEqual("error", expect, "\(at): unexpected \(error.code)")
            XCTAssertEqual(try Vectors.string(step, "error"), error.code, "\(at): stable code")
        }
    }

    private func outcomeName(_ outcome: FragmentReassembler.Outcome) -> String {
        switch outcome {
        case .accepted: return "accepted"
        case .duplicate: return "duplicate"
        case .complete: return "complete"
        }
    }

    private func fragmentsHex(_ c: [String: Any]) throws -> [String] {
        guard let raw = c["fragments_hex"] as? [String] else {
            throw LumosCrypto.WireError("TESTONLY", "fragments_hex is not a string array")
        }
        return raw
    }
}

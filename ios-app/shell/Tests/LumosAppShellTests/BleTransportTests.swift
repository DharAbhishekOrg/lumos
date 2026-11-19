/// Lumos - ios-app/shell / Pacing, the frame channel and the Apple triage, executed.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TRN-004, TRS-TRN-002, TRS-PROTO-010, TRS-ENV-001, TRS-ENV-002, TRS-TST-001
/// ADRs: ADR-002
/// Threading: test-only
/// Notes: The Apple half of the S2b discipline: every behaviour with logic in
/// it runs here without a radio. The pacer executes TRS-TRN-004 verbatim
/// against a scripted canSend gate; the frame channel round-trips blobs over
/// the vector-pinned fragmentation layer; and the synthetic venue - 100+
/// advertisers built through the same Adv code Android radios use, iOS
/// UUID-only peers, and non-Lumos noise - holds the triage to the same
/// bounded, sorted, aged behaviour its Android sibling proves, because a
/// venue full of mixed phones is one radio environment.

import XCTest
import Foundation
import LumosProtocol
import LumosProtocolGenerated
@testable import LumosAppShell

final class PacedWriteQueueTests: XCTestCase {

    private var wire: [Data] = []
    private var gateOpen = true

    private func newQueue() -> PacedWriteQueue {
        PacedWriteQueue(canSend: { self.gateOpen }, write: { self.wire.append($0) })
    }

    func testWritesFlowWhileTheGateSaysYes() {
        let queue = newQueue()
        queue.enqueue(Data([1]))
        queue.enqueue(Data([2]))
        XCTAssertEqual([Data([1]), Data([2])], wire)
        XCTAssertEqual(0, queue.queued)
    }

    func testWritesPauseTheInstantTheGateSaysNo() {
        // TRS-TRN-004: queue writes and pause when canSendWriteWithoutResponse
        // is false - CoreBluetooth drops unpaced writes silently.
        let queue = newQueue()
        gateOpen = false
        queue.enqueue(Data([1]))
        queue.enqueue(Data([2]))
        XCTAssertTrue(wire.isEmpty, "nothing may be written against a closed gate")
        XCTAssertEqual(2, queue.queued)

        gateOpen = true
        queue.resume()
        XCTAssertEqual([Data([1]), Data([2])], wire, "peripheralIsReady drains in order")
    }

    func testReconnectDiscardsTheOldConnectionsWrites() {
        let queue = newQueue()
        gateOpen = false
        queue.enqueue(Data([1]))
        let before = queue.epoch
        XCTAssertEqual(1, queue.advanceEpoch(), "the queued write is discarded")
        XCTAssertEqual(before + 1, queue.epoch)
        gateOpen = true
        queue.resume()
        XCTAssertTrue(wire.isEmpty, "a write from the old connection must not reach the new one")
    }
}

final class FrameChannelTests: XCTestCase {

    private let timeoutMillis = Int64(LumosConstants.reassemblyTimeoutSecBLE) * 1000

    func testABlobCrossesTheChannelWholeAndPaced() throws {
        var wire: [Data] = []
        var received: [Data] = []
        var gateOpen = true
        let queue = PacedWriteQueue(canSend: { gateOpen }, write: { wire.append($0) })
        let sender = FrameChannel(queue: queue,
                                  reassembler: FragmentReassembler(timeoutMillis: timeoutMillis),
                                  onBlob: { _ in })
        let receiver = FrameChannel(
            queue: PacedWriteQueue(canSend: { true }, write: { _ in }),
            reassembler: FragmentReassembler(timeoutMillis: timeoutMillis),
            onBlob: { received.append($0) })

        let blob = Data((0..<500).map { UInt8($0 % 251) })

        // The gate closes mid-blob: the tail waits, resume releases it.
        gateOpen = false
        try sender.send(blob, negotiatedMtu: 69)
        XCTAssertTrue(wire.isEmpty)
        gateOpen = true
        queue.resume()
        XCTAssertTrue(wire.count > 1, "the blob must actually fragment at this MTU")

        for fragment in wire {
            receiver.onFragmentReceived(fragment, nowMillis: 0)
        }
        XCTAssertEqual([blob], received)
    }

    func testInboundGarbageDropsSilentlyUnderItsStableCode() {
        let receiver = FrameChannel(
            queue: PacedWriteQueue(canSend: { true }, write: { _ in }),
            reassembler: FragmentReassembler(timeoutMillis: timeoutMillis),
            onBlob: { _ in XCTFail("garbage must never become a blob") })
        receiver.onFragmentReceived(Data([0x00, 0x09, 0x00, 0x00, 0x01]), nowMillis: 0)
        XCTAssertEqual(1, receiver.drops(LumosErrors.errFragSequenceGap),
                       "an unknown continuation is counted, never thrown at the radio loop")
    }
}

final class DiscoveryTriageTests: XCTestCase {

    private let triage = DiscoveryTriage()

    private func kAdv(_ peer: Int) -> Data {
        Data((0..<32).map { UInt8((peer * 31 + $0) % 256) })
    }

    private func hinted(_ peer: Int, rssi: Int, at: Int64) throws -> BleSighting {
        BleSighting(linkToken: "and:\(peer)",
                    serviceData: try Adv.build(kAdv: kAdv(peer), modeFlags: 0, epoch: 1),
                    rssiDbm: rssi, atMillis: at)
    }

    func testAHundredPlusAdvertisersTriageBoundedAndSorted() throws {
        // TRS-ENV-001: at least 100 concurrent advertisers in radio range.
        for peer in 0..<100 {
            triage.ingest(try hinted(peer, rssi: -40 - peer, at: 1000))
        }
        for peer in 0..<15 {
            triage.ingest(BleSighting(linkToken: "ios:\(peer)", serviceData: nil,
                                      rssiDbm: -50 - peer, atMillis: 1000))
        }
        for noise in 0..<10 {
            triage.ingest(BleSighting(linkToken: "noise:\(noise)",
                                      serviceData: Data([1, 2, 3]),
                                      rssiDbm: -30, atMillis: 1000))
        }

        XCTAssertEqual(115, triage.count, "every Lumos-shaped sighting is a candidate")
        XCTAssertEqual(10, triage.structurallyDiscarded, "the discard path must actually run")

        let ordered = triage.candidates()
        for i in 1..<ordered.count {
            XCTAssertGreaterThanOrEqual(ordered[i - 1].rssiDbm, ordered[i].rssiDbm,
                                        "candidates must come strongest first")
        }

        XCTAssertEqual(LumosConstants.maxConcurrentGATTLinksDefault,
                       triage.nextConnects(activeLinks: 0).count)
        XCTAssertTrue(triage.nextConnects(
            activeLinks: LumosConstants.maxConcurrentGATTLinksDefault).isEmpty,
                      "a full table spends nothing")
    }

    func testRepeatSightingsOfOnePeerStayOneCandidate() throws {
        triage.ingest(try hinted(1, rssi: -80, at: 1000))
        triage.ingest(try hinted(1, rssi: -60, at: 2000))
        XCTAssertEqual(1, triage.count, "the rotating EID is the dedup key")
        XCTAssertEqual(-60, triage.candidates()[0].rssiDbm, "the strongest recent signal wins")
    }

    func testCandidatesAgeOutOnTheRegisterBound() throws {
        triage.ingest(try hinted(1, rssi: -50, at: 0))
        triage.ingest(try hinted(2, rssi: -50, at: 5000))
        let bound = Int64(LumosConstants.presenceAgeoutSecDefault) * 1000
        XCTAssertEqual(0, triage.prune(nowMillis: bound), "exactly at the bound is still live")
        XCTAssertEqual(1, triage.prune(nowMillis: bound + 1), "past the bound, the stale one goes")
        XCTAssertEqual(1, triage.count)
    }

    func testRssiBandsFollowTheRegisterThresholds() throws {
        let veryClose = triage.ingest(try hinted(
            1, rssi: LumosConstants.rssiBandsDBMVeryCloseGte, at: 0))
        let nearby = triage.ingest(try hinted(
            2, rssi: LumosConstants.rssiBandsDBMNearbyGte, at: 0))
        let far = triage.ingest(try hinted(
            3, rssi: LumosConstants.rssiBandsDBMNearbyGte - 1, at: 0))
        XCTAssertEqual(.veryClose, veryClose?.band)
        XCTAssertEqual(.nearby, nearby?.band)
        XCTAssertEqual(.far, far?.band)
    }
}

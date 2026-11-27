/// Lumos - ios-app/shell / One link's handshake and phones finding each other, on iOS.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-PROTO-017, TRS-ENV-001, TRS-ENV-002, SRS-NBY-005, SRS-NBY-006, SRS-NBY-007, SRS-NBY-017
/// ADRs: ADR-002, ADR-013
/// Threading: test-only
/// Notes: The Swift half of the Android LinkSessionTest and
/// DiscoveryEngineTest, with real Curve25519 identities and real frames: two
/// sessions back to back, then phones on an in-memory air that routes
/// connections and whole frames as the radio would.

import Foundation
import CryptoKit
import XCTest
import LumosProtocol
import LumosProtocolGenerated
@testable import LumosAppShell

/// One identity; a fresh exchange per link.
private struct Party {
    let fields: Hello.Fields
    let signing: Curve25519.Signing.PrivateKey

    init(index: Int) throws {
        let signing = Curve25519.Signing.PrivateKey()
        let xPublic = Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation
        let binding = try signing.signature(for: Data(LumosConstants.x3dhIdentityBindingInfoValue.utf8) + xPublic)
        var eid = Data(count: Hello.eidLen)
        eid[0] = UInt8(index & 0xFF)
        self.signing = signing
        self.fields = Hello.Fields(protocolVersion: LumosConstants.protocolVersionEncoded, capabilityBits: index,
                                   venueHint: 0, eid: eid, ed25519Public: signing.publicKey.rawRepresentation,
                                   x25519Public: xPublic, bindingSignature: binding,
                                   nonce: Data(count: Hello.nonceLen))
    }

    func freshExchange() -> HelloExchange {
        let key = signing
        return HelloExchange(signer: { try key.signature(for: $0) }, own: fields)
    }
}

private func freshNonce() -> Data {
    Data((0..<Hello.nonceLen).map { _ in UInt8.random(in: 0...255) })
}

private func frame(_ kind: MessageClass, _ payload: Data) throws -> Data {
    try FrameCodec.encode(header: FrameHeader(type: kind.rawValue, sessionID: Data(count: 16),
                                              messageID: Data(count: 16)), payload: payload)
}

final class LinkSessionTests: XCTestCase {

    private let timeout: Int64 = 10_000
    private let t0: Int64 = 1_000_000
    private var toPeripheral: [Data] = []
    private var toCentral: [Data] = []

    private func central(_ who: Party) -> LinkSession {
        LinkSession(role: .central, exchange: who.freshExchange(), outbox: { [unowned self] in toPeripheral.append($0) },
                    nonces: freshNonce, timeoutMillis: timeout)
    }

    private func peripheral(_ who: Party) -> LinkSession {
        LinkSession(role: .peripheral, exchange: who.freshExchange(), outbox: { [unowned self] in toCentral.append($0) },
                    nonces: freshNonce, timeoutMillis: timeout)
    }

    private func pump(_ c: LinkSession, _ p: LinkSession) {
        while !toPeripheral.isEmpty || !toCentral.isEmpty {
            if !toPeripheral.isEmpty { p.onFrame(toPeripheral.removeFirst(), nowMillis: t0) }
            if !toCentral.isEmpty { c.onFrame(toCentral.removeFirst(), nowMillis: t0) }
        }
    }

    func testBothEndsFindEachOtherWhoeverOpensFirst() throws {
        let alice = try Party(index: 1)
        let bob = try Party(index: 3)
        for centralFirst in [true, false] {
            let c = central(alice)
            let p = peripheral(bob)
            if !centralFirst { p.open(nowMillis: t0) }
            c.open(nowMillis: t0)
            pump(c, p)
            XCTAssertEqual(c.state, .done)
            XCTAssertEqual(p.state, .done)
            XCTAssertEqual(c.peer?.id, Hello.peerId(bob.fields.ed25519Public), "the central found the peripheral")
            XCTAssertEqual(p.peer?.id, Hello.peerId(alice.fields.ed25519Public), "and the peripheral the central")
            XCTAssertEqual(c.peer?.fields.capabilityBits, 3, "authenticated capabilities, not hinted ones")
        }
    }

    func testASilentPeerEndsTheLinkOnTheTimer() throws {
        let c = central(try Party(index: 1))
        c.open(nowMillis: t0)
        c.tick(nowMillis: t0 + timeout - 1)
        XCTAssertEqual(c.state, .exchanging)
        c.tick(nowMillis: t0 + timeout)
        XCTAssertEqual(c.state, .failed)
        XCTAssertTrue(c.timedOut)
        XCTAssertNil(c.peer)
    }

    func testForgedReplayedAndForeignMessagesGrantNothing() throws {
        let alice = try Party(index: 1)
        let bob = try Party(index: 2)

        let forged = central(alice)
        forged.open(nowMillis: t0)
        let myHello = try FrameCodec.decode(toPeripheral.removeFirst()).payload
        var ack = try bob.freshExchange().onHello(myHello)
        ack[ack.count - 1] ^= 1
        forged.onFrame(try frame(.helloAck, ack), nowMillis: t0)
        XCTAssertEqual(forged.state, .failed)
        XCTAssertNil(forged.peer)

        let p = peripheral(bob)
        let hello = alice.freshExchange().start(nonce: Data(count: Hello.nonceLen))
        p.onFrame(try frame(.hello, hello), nowMillis: t0)
        XCTAssertEqual(p.state, .exchanging)
        p.onFrame(try frame(.hello, hello), nowMillis: t0)
        XCTAssertEqual(p.state, .failed)
        XCTAssertEqual(p.failure, LumosErrors.errCryptoReplay, "a second HELLO on one link is a replay")

        let text = central(alice)
        text.open(nowMillis: t0)
        text.onFrame(try frame(.textMessage, Data([1, 2, 3])), nowMillis: t0)
        XCTAssertEqual(text.failure, LumosErrors.errFrameUnknownType, "a discovery link carries the handshake only")

        let garbled = central(alice)
        garbled.open(nowMillis: t0)
        garbled.onFrame(Data([0x13, 0x37, 0, 0]), nowMillis: t0)
        XCTAssertEqual(garbled.state, .failed)
    }

    func testAMirrorOfOurOwnIdentityIsNotAPeer() throws {
        let alice = try Party(index: 1)
        let c = central(alice)
        let p = peripheral(alice)
        c.open(nowMillis: t0)
        pump(c, p)
        XCTAssertEqual(c.state, .failed)
        XCTAssertNil(c.peer)
    }
}

/// Phones on an in-memory air.
private final class Air {
    var phones: [String: Phone] = [:]
    var route: [String: (Phone, String)] = [:]
    var pending: [() -> Void] = []
    var maxLinks = 0
    var serverHandles = 0
    var now: Int64 = 1_000_000

    func flush() {
        while !pending.isEmpty {
            pending.removeFirst()()
            maxLinks = max(maxLinks, phones.values.map { $0.engine.activeLinks }.max() ?? 0)
        }
    }

    func scan() {
        for scanner in phones.values {
            for advertiser in phones.values where scanner !== advertiser && advertiser.advertising {
                scanner.engine.onSighting(BleSighting(linkToken: advertiser.token, serviceData: nil, rssiDbm: advertiser.rssi,
                                                      atMillis: now))
            }
        }
    }

    func round() {
        scan()
        for phone in phones.values { phone.engine.tick(nowMillis: now) }
        flush()
    }
}

private final class Phone: DiscoveryRadio {
    let token: String
    unowned let air: Air
    var engine: DiscoveryEngine!
    var advertising = true
    var silent = false
    var rssi = -50

    init(_ air: Air, _ token: String, _ index: Int) throws {
        self.air = air
        self.token = token
        let party = try Party(index: index)
        engine = DiscoveryEngine(radio: self, freshExchange: party.freshExchange, nonces: freshNonce,
                                 handshakeTimeoutMillis: 10_000)
        air.phones[token] = self
        engine.start()
    }

    func connect(handle: String, linkToken: String) {
        guard let target = air.phones[linkToken] else { return }
        air.serverHandles += 1
        let server = "s\(air.serverHandles)"
        air.route["\(token)/\(handle)"] = (target, server)
        air.route["\(linkToken)/\(server)"] = (self, handle)
        air.pending.append { [unowned self] in
            if !target.silent { target.engine.onCentralSubscribed(handle: server, nowMillis: self.air.now) }
            self.engine.onLinkReady(handle: handle, nowMillis: self.air.now)
        }
    }

    func send(handle: String, frame: Data) {
        guard let (receiver, theirs) = air.route["\(token)/\(handle)"] else { return }
        air.pending.append { [unowned self] in
            if !receiver.silent { receiver.engine.onFrame(handle: theirs, frame: frame, nowMillis: self.air.now) }
        }
    }

    func disconnect(handle: String) {
        guard let (other, theirs) = air.route.removeValue(forKey: "\(token)/\(handle)") else { return }
        air.route["\(other.token)/\(theirs)"] = nil
        air.pending.append { other.engine.onLinkClosed(handle: theirs) }
    }
}

final class DiscoveryEngineTests: XCTestCase {

    func testTwoPhonesFindEachOtherAndLetTheLinkGo() throws {
        let air = Air()
        let iphone = try Phone(air, "iphone", 1)
        let oneplus = try Phone(air, "oneplus", 2)
        air.round()
        XCTAssertEqual(iphone.engine.cards.count, 1, "the iPhone shows the OnePlus")
        XCTAssertEqual(oneplus.engine.cards.count, 1, "and the OnePlus shows the iPhone")
        XCTAssertEqual(iphone.engine.activeLinks + oneplus.engine.activeLinks, 0, "finished links close at once")
    }

    func testAVenueIsFoundWithoutEverExceedingTheLinkBudget() throws {
        let air = Air()
        let venue = try (0..<12).map { try Phone(air, "p\($0)", 10 + $0) }
        var rounds = 0
        while rounds < 40 && !venue.allSatisfy({ $0.engine.cards.count == 11 }) {
            air.round()
            air.now += 1000
            rounds += 1
        }
        for phone in venue {
            XCTAssertEqual(phone.engine.cards.count, 11, "every phone shows every other, once each")
        }
        XCTAssertLessThanOrEqual(air.maxLinks, LumosConstants.maxConcurrentGATTLinksDefault,
                                 "no phone ever held more links than the register budget")
    }

    func testKnownStrongPeersDoNotStarveTheOnesStillToBeFound() throws {
        let air = Air()
        let me = try Phone(air, "me", 1)
        let near = try (0..<3).map { try Phone(air, "near\($0)", 2 + $0) }
        let far = try (0..<3).map { try Phone(air, "far\($0)", 5 + $0) }
        near.forEach { $0.rssi = -40 }
        far.forEach { $0.rssi = -80 }
        for wave in 1...2 {
            air.scan()
            me.engine.tick(nowMillis: air.now)
            air.flush()
            XCTAssertEqual(me.engine.cards.count, 3 * wave, "each wave finds three new peers")
        }
    }

    func testAPresentPeerIsNotConnectedAgainUntilItsCardAgesOut() throws {
        let air = Air()
        let a = try Phone(air, "a", 1)
        let b = try Phone(air, "b", 2)
        b.advertising = false
        air.round()
        XCTAssertEqual(a.engine.cards.count, 1)
        let before = air.serverHandles
        air.now += 5000
        air.round()
        XCTAssertEqual(air.serverHandles, before, "no new link while the card is fresh")
        air.now += Int64(LumosConstants.presenceAgeoutSecDefault) * 1000 + 1
        a.engine.tick(nowMillis: air.now)
        XCTAssertTrue(a.engine.cards.isEmpty, "the card ages out")
        air.round()
        XCTAssertEqual(air.serverHandles, before + 1, "and then it is found again")
    }

    func testASilentPeerCostsOneBoundedLinkAndStopEndsEverything() throws {
        let air = Air()
        let a = try Phone(air, "a", 1)
        let mute = try Phone(air, "mute", 2)
        mute.silent = true
        air.scan()
        a.engine.tick(nowMillis: air.now)
        air.flush()
        XCTAssertEqual(a.engine.activeLinks, 1, "waiting for a HELLO that never comes")
        air.now += 10_000
        a.engine.tick(nowMillis: air.now)
        XCTAssertEqual(a.engine.activeLinks, 0, "the timer frees the budget")
        XCTAssertTrue(a.engine.cards.isEmpty)

        air.scan()
        a.engine.tick(nowMillis: air.now)
        air.flush()
        a.engine.stop()
        XCTAssertEqual(a.engine.activeLinks, 0, "the one-action stop closes every link")
        air.scan()
        a.engine.tick(nowMillis: air.now)
        a.engine.onCentralSubscribed(handle: "s99", nowMillis: air.now)
        XCTAssertEqual(a.engine.activeLinks, 0, "and nothing starts or gets in")
    }
}

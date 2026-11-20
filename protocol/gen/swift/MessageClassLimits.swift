/// Lumos - protocol/gen/swift / Generated per-class maximum payload sizes.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-CONST-001, TRS-CONST-002, TRS-CONST-005, TRS-REP-002
/// ADRs: ADR-002, ADR-004
/// Threading: any
/// GENERATED - DO NOT EDIT. Generator: tools/constantsgen
/// Source register: protocol/constants/lumos_constants.json
/// Baseline: TRS v1.1 Sec. 23 / EDP v1.0 Sec. 4 (ADR-004)
/// constants_hash: sha256:4fb89c6c38259e018245239b69a55a6eb61c015ce5f49709774bd2618f384cb4

/// Per-class payload bounds resolved from the constants register (TRS-PROTO-007).
public enum MessageClassLimits {

    /// Maximum ciphertext payload in bytes, by message class.
    public static let maxPayloadBytes: [MessageClass: Int] = [
        .hello: 1228,
        .helloAck: 1228,
        .interestRequest: 1228,
        .interestResponse: 1228,
        .profilePreview: 65536,
        .profileFull: 2097152,
        .textMessage: 32768,
        .deliveryReceipt: 1228,
        .readReceipt: 1228,
        .typing: 1228,
        .objectMeta: 1228,
        .objectChunk: 65536,
        .objectAck: 1228,
        .objectCancel: 1228,
        .voiceMeta: 1228,
        .meshRelay: 32768,
        .storeForward: 32768,
        .venuePackageHint: 1228,
        .venueRoomEvent: 1228,
        .voucherToken: 1228,
        .heartbeat: 1228,
        .error: 1228,
    ]
}

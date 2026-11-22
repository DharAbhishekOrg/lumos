/// Lumos - LumosAppShell / The strict JSON subset the relay speaks, and its hex fields.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-BCK-001, SRS-RMT-009
/// ADRs: ADR-001
/// Threading: any
/// Notes: The Swift port of the Android Json and Hex. The registry and relay
/// speak small, flat JSON: strings (hex and base64), integers, booleans, one
/// level of objects and arrays. This codec reads exactly that and refuses
/// everything else, so nothing a hostile response sends can get further than
/// a malformed-payload refusal: depth is bounded, a key may appear once,
/// numbers must be integers that fit an Int64, and nothing may trail the
/// value. Foundation's JSONSerialization is deliberately NOT used: it accepts
/// duplicate keys, fractions and exponents, which the Android port refuses,
/// and the two platforms must refuse the same responses. Parsing walks UTF-16
/// code units, as the Java port's String does. Unknown keys are kept and
/// ignored by the caller, so a newer server can add fields.

import Foundation

/// Strict JSON for the relay routes.
public enum RelayJson {

    static let maxDepth = 8

    /// The text is not the JSON this codec accepts.
    public struct Malformed: Error, Equatable, Sendable {
        /// Non-oracle detail for humans.
        public let detail: String
    }

    /// A parsed value.
    public indirect enum Value: Equatable, Sendable {
        case string(String)
        case integer(Int64)
        case bool(Bool)
        case null
        case object(Obj)
        case array([Value])
    }

    /// A parsed object, read field by field with the expected type.
    public struct Obj: Equatable, Sendable {
        let fields: [String: Value]

        /// Whether the key is present (even as null).
        public func has(_ key: String) -> Bool {
            fields[key] != nil
        }

        private func require(_ key: String) throws -> Value {
            guard let v = fields[key] else { throw Malformed(detail: "missing field") }
            return v
        }

        /// A string field.
        public func string(_ key: String) throws -> String {
            guard case .string(let s) = try require(key) else { throw Malformed(detail: "field is not a string") }
            return s
        }

        /// An integer field.
        public func integer(_ key: String) throws -> Int64 {
            guard case .integer(let n) = try require(key) else { throw Malformed(detail: "field is not an integer") }
            return n
        }

        /// A boolean field.
        public func bool(_ key: String) throws -> Bool {
            guard case .bool(let b) = try require(key) else { throw Malformed(detail: "field is not a boolean") }
            return b
        }

        /// An object field.
        public func object(_ key: String) throws -> Obj {
            guard case .object(let o) = try require(key) else { throw Malformed(detail: "field is not an object") }
            return o
        }

        /// An object field that may be absent or null.
        public func optObject(_ key: String) throws -> Obj? {
            switch fields[key] {
            case nil, .null?: return nil
            case .object(let o)?: return o
            default: throw Malformed(detail: "field is not an object")
            }
        }

        /// An array field whose items are all objects.
        public func objects(_ key: String) throws -> [Obj] {
            guard case .array(let items) = try require(key) else { throw Malformed(detail: "field is not an array") }
            return try items.map {
                guard case .object(let o) = $0 else { throw Malformed(detail: "array item is not an object") }
                return o
            }
        }
    }

    /// Parses a document whose top level must be an object.
    /// - Throws: ``Malformed`` for anything outside the subset
    public static func parseObject(_ text: String) throws -> Obj {
        var r = Reader(Array(text.utf16))
        r.skipSpace()
        let v = try r.value(depth: 0)
        r.skipSpace()
        guard r.atEnd else { throw Malformed(detail: "trailing content") }
        guard case .object(let o) = v else { throw Malformed(detail: "top level is not an object") }
        return o
    }

    private struct Reader {
        private let s: [UInt16]
        private var i = 0

        init(_ s: [UInt16]) {
            self.s = s
        }

        var atEnd: Bool { i >= s.count }

        mutating func skipSpace() {
            while i < s.count, [0x20, 0x09, 0x0A, 0x0D].contains(s[i]) {
                i += 1
            }
        }

        func peek() throws -> UInt16 {
            guard !atEnd else { throw Malformed(detail: "unexpected end") }
            return s[i]
        }

        mutating func expect(_ c: Character) throws {
            guard try peek() == c.utf16.first else { throw Malformed(detail: "unexpected character") }
            i += 1
        }

        mutating func value(depth: Int) throws -> Value {
            guard depth <= RelayJson.maxDepth else { throw Malformed(detail: "nested too deeply") }
            switch try peek() {
            case 0x7B: return .object(try object(depth: depth))
            case 0x5B: return .array(try array(depth: depth))
            case 0x22: return .string(try string())
            case 0x74: try literal("true"); return .bool(true)
            case 0x66: try literal("false"); return .bool(false)
            case 0x6E: try literal("null"); return .null
            default: return .integer(try number())
            }
        }

        mutating func literal(_ word: String) throws {
            let w = Array(word.utf16)
            guard s.count - i >= w.count, Array(s[i..<i + w.count]) == w else {
                throw Malformed(detail: "unknown literal")
            }
            i += w.count
        }

        mutating func object(depth: Int) throws -> Obj {
            try expect("{")
            var fields: [String: Value] = [:]
            skipSpace()
            if try peek() == 0x7D {
                i += 1
                return Obj(fields: fields)
            }
            while true {
                skipSpace()
                let key = try string()
                skipSpace()
                try expect(":")
                skipSpace()
                let v = try value(depth: depth + 1)
                guard fields.updateValue(v, forKey: key) == nil else { throw Malformed(detail: "duplicate key") }
                skipSpace()
                if try peek() == 0x2C {
                    i += 1
                    continue
                }
                try expect("}")
                return Obj(fields: fields)
            }
        }

        mutating func array(depth: Int) throws -> [Value] {
            try expect("[")
            var items: [Value] = []
            skipSpace()
            if try peek() == 0x5D {
                i += 1
                return items
            }
            while true {
                skipSpace()
                items.append(try value(depth: depth + 1))
                skipSpace()
                if try peek() == 0x2C {
                    i += 1
                    continue
                }
                try expect("]")
                return items
            }
        }

        mutating func string() throws -> String {
            try expect("\"")
            var units: [UInt16] = []
            while true {
                let c = try peek()
                i += 1
                if c == 0x22 { return String(decoding: units, as: UTF16.self) }
                guard c >= 0x20 else { throw Malformed(detail: "control character in string") }
                units.append(c == 0x5C ? try escape() : c)
            }
        }

        mutating func escape() throws -> UInt16 {
            let e = try peek()
            i += 1
            switch e {
            case 0x22, 0x5C, 0x2F: return e
            case 0x62: return 0x08
            case 0x66: return 0x0C
            case 0x6E: return 0x0A
            case 0x72: return 0x0D
            case 0x74: return 0x09
            case 0x75:
                guard s.count - i >= 4 else { throw Malformed(detail: "short unicode escape") }
                var code: UInt16 = 0
                for _ in 0..<4 {
                    guard let d = RelayHex.nibble(s[i]) else { throw Malformed(detail: "bad unicode escape") }
                    code = code << 4 | UInt16(d)
                    i += 1
                }
                return code
            default:
                throw Malformed(detail: "unknown escape")
            }
        }

        mutating func number() throws -> Int64 {
            let start = i
            if !atEnd, s[i] == 0x2D {
                i += 1
            }
            let digits = i
            while !atEnd, (0x30...0x39).contains(s[i]) {
                i += 1
            }
            guard i > digits else { throw Malformed(detail: "not a value") }
            guard !(s[digits] == 0x30 && i - digits > 1) else { throw Malformed(detail: "leading zero") }
            if !atEnd, [0x2E, 0x65, 0x45].contains(s[i]) {
                throw Malformed(detail: "only integers are accepted")
            }
            guard let n = Int64(String(decoding: s[start..<i], as: UTF16.self)) else {
                throw Malformed(detail: "integer out of range")
            }
            return n
        }
    }

    // MARK: writing

    /// Builds one flat object, in insertion order.
    public struct Writer {
        private var text = "{"
        private var first = true

        public init() {}

        private mutating func key(_ k: String) {
            if !first { text += "," }
            first = false
            text += RelayJson.quote(k) + ":"
        }

        /// Adds a string field.
        public mutating func put(_ k: String, _ v: String) {
            key(k)
            text += RelayJson.quote(v)
        }

        /// Adds an integer field.
        public mutating func put(_ k: String, _ v: Int64) {
            key(k)
            text += String(v)
        }

        /// Adds a field whose value is already JSON.
        public mutating func putRaw(_ k: String, _ json: String) {
            key(k)
            text += json
        }

        /// The finished object.
        public func end() -> String {
            text + "}"
        }
    }

    /// An array of already-encoded items.
    public static func array(_ items: [String]) -> String {
        "[" + items.joined(separator: ",") + "]"
    }

    /// An array of strings.
    public static func stringArray(_ items: [String]) -> String {
        "[" + items.map(quote).joined(separator: ",") + "]"
    }

    static func quote(_ v: String) -> String {
        var out = "\""
        for u in v.utf16 {
            switch u {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case ..<0x20: out += "\\u00" + RelayHex.encode(Data([UInt8(u)]))
            default: out += String(decoding: [u], as: UTF16.self)
            }
        }
        // Lone surrogates are not reachable here: every caller writes hex,
        // base64 or a registry code.
        return out + "\""
    }
}

/// Lowercase hex for the relay's key and id fields, ASCII digits only.
public enum RelayHex {
    private static let digits = Array("0123456789abcdef".utf8)

    /// Lowercase hex.
    public static func encode(_ b: Data) -> String {
        String(b.flatMap { [digits[Int($0 >> 4)], digits[Int($0 & 0x0F)]] }.map { Character(Unicode.Scalar($0)) })
    }

    /// Exactly n bytes of hex, either case, nothing else.
    /// - Throws: ``RelayJson/Malformed`` on a wrong length or a non-hex character
    public static func decode(_ s: String, count n: Int) throws -> Data {
        let u = Array(s.utf16)
        guard u.count == n * 2 else { throw RelayJson.Malformed(detail: "hex field has the wrong length") }
        var out = Data(capacity: n)
        for k in 0..<n {
            guard let hi = nibble(u[2 * k]), let lo = nibble(u[2 * k + 1]) else {
                throw RelayJson.Malformed(detail: "not hex")
            }
            out.append(hi << 4 | lo)
        }
        return out
    }

    /// One ASCII hex digit's value, or nil.
    static func nibble(_ c: UInt16) -> UInt8? {
        switch c {
        case 0x30...0x39: return UInt8(c - 0x30)
        case 0x61...0x66: return UInt8(c - 0x61 + 10)
        case 0x41...0x46: return UInt8(c - 0x41 + 10)
        default: return nil
        }
    }
}

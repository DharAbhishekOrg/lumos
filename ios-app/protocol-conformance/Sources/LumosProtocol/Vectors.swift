/// Lumos - protocol-conformance / Committed golden-vector loader.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: TRS-TST-001, TRS-CONST-005, TRS-PROTO-015
/// ADRs: ADR-004
/// Threading: any
/// Notes: Loads the COMMITTED /test-vectors files and never generates anything
/// (single-generator rule). A missing file is a hard failure: TRS Sec. 18.4 is
/// explicit that a skipped vector file is a failed gate, so there is deliberately
/// no "skip if absent" path. Every file's constants_hash is checked against the
/// generated register, so stale vectors fail loudly instead of passing against an
/// old protocol.

import Foundation
import LumosProtocolGenerated

/// Reads and validates the committed vector files.
public enum Vectors {

    /// A failure loading or validating a vector file.
    public struct LoadError: Error, CustomStringConvertible {
        /// What went wrong.
        public let description: String
        /// Creates a load error.
        public init(_ description: String) { self.description = description }
    }

    /// The repository root, located from this source file's path.
    ///
    /// `swift test` runs with an unspecified working directory, so the root is
    /// derived from `#filePath` rather than assumed. That keeps the runner
    /// working under both `swift test` and Xcode.
    public static func repoRoot(from filePath: StaticString = #filePath) -> URL {
        // .../ios-app/protocol-conformance/Sources/LumosProtocol/Vectors.swift
        var url = URL(fileURLWithPath: filePath.description)
        for _ in 0..<5 {
            url = url.deletingLastPathComponent()
        }
        return url
    }

    /// Loads a vector file and verifies it is current.
    /// - Parameters:
    ///   - relativePath: path under the repository root
    ///   - root: the repository root
    /// - Returns: the parsed root object
    /// - Throws: ``LoadError`` when missing, unparseable or stale
    public static func load(_ relativePath: String, root: URL) throws -> [String: Any] {
        let url = root.appendingPathComponent(relativePath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw LoadError(
                "committed vector file is missing: \(relativePath) "
                + "- a skipped vector file is a FAILED GATE (TRS Sec. 18.4)")
        }
        let data = try Data(contentsOf: url)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw LoadError("vector file \(relativePath) is not a JSON object")
        }
        guard let hash = object["constants_hash"] as? String else {
            throw LoadError("vector file \(relativePath) has no constants_hash")
        }
        guard hash == LumosConstants.constantsHash else {
            throw LoadError(
                "stale vector file \(relativePath): constants_hash \(hash) does not match "
                + "this build's \(LumosConstants.constantsHash) (TRS-CONST-005)")
        }
        return object
    }

    /// - Parameters:
    ///   - object: containing object
    ///   - key: field name
    /// - Returns: the field decoded from hex
    /// - Throws: when absent or malformed
    public static func hex(_ object: [String: Any], _ key: String) throws -> Data {
        guard let text = object[key] as? String else {
            throw LoadError("missing hex field \(key)")
        }
        guard let bytes = Hex.decode(text) else {
            throw LoadError("malformed hex in \(key)")
        }
        return bytes
    }

    /// - Parameters:
    ///   - object: containing object
    ///   - key: field name
    /// - Returns: the nested object
    /// - Throws: when absent or not an object
    public static func object(_ object: [String: Any], _ key: String) throws -> [String: Any] {
        guard let o = object[key] as? [String: Any] else {
            throw LoadError("missing object field \(key)")
        }
        return o
    }

    /// - Parameters:
    ///   - object: containing object
    ///   - key: field name
    /// - Returns: the array of objects
    /// - Throws: when absent
    public static func array(_ object: [String: Any], _ key: String) throws -> [[String: Any]] {
        guard let a = object[key] as? [[String: Any]] else {
            throw LoadError("missing array field \(key)")
        }
        return a
    }

    /// - Parameters:
    ///   - object: containing object
    ///   - key: field name
    /// - Returns: the string value
    /// - Throws: when absent
    public static func string(_ object: [String: Any], _ key: String) throws -> String {
        guard let s = object[key] as? String else {
            throw LoadError("missing string field \(key)")
        }
        return s
    }

    /// - Parameters:
    ///   - object: containing object
    ///   - key: field name
    /// - Returns: the integer value
    /// - Throws: when absent
    public static func int(_ object: [String: Any], _ key: String) throws -> Int {
        guard let n = object[key] as? NSNumber else {
            throw LoadError("missing numeric field \(key)")
        }
        return n.intValue
    }

    /// - Parameters:
    ///   - object: containing object
    ///   - key: field name
    /// - Returns: the boolean value, defaulting to false
    public static func bool(_ object: [String: Any], _ key: String) -> Bool {
        (object[key] as? NSNumber)?.boolValue ?? false
    }
}

/// Lumos - ios-app/shell / Metadata stripping and validation for profile images.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-005, SRS-PRO-006, SRS-NFR-PRI-008, TRS-MED-002
/// ADRs: ADR-004
/// Threading: any
/// Notes: The same byte-level segment rewriter as the Android side, and for the
/// same reason: metadata in JPEG lives in APPn segments and in PNG in ancillary
/// chunks, so it can be removed by rewriting the container without decoding a
/// pixel - which makes the whole strip pure and testable everywhere.
///
/// iOS gets one thing Android's JVM tests cannot: ImageIO is available on
/// macOS, so `reencode` below performs a REAL decode-and-re-encode and CI
/// executes it. That is the second half of the WP-02 brief's pipeline, and it
/// adds what a segment strip cannot - it destroys anything hidden in the
/// entropy-coded data itself, and it normalises a file whose structure was
/// unusual. The Android equivalent runs on the emulator job.
///
/// Size limits come from the Sec. 23 register via the generated constants
/// (ADR-004); no literal byte count appears in this file.

import CoreGraphics
import Foundation
import ImageIO
import LumosProtocolGenerated
import UniformTypeIdentifiers

/// Strips metadata from and validates profile images.
public enum MediaSanitizer {

    /// The image types accepted for profile media.
    public enum ImageType: String, Sendable {
        /// JFIF/EXIF JPEG.
        case jpeg = "image/jpeg"
        /// PNG.
        case png = "image/png"
    }

    /// Why an image was rejected (SRS-PRO-006).
    public enum Rejection: Error, Equatable, Sendable {
        /// Not a type this product accepts.
        case unsupportedType
        /// Truncated or structurally invalid.
        case malformed(String)
        /// Larger than the register's full-image bound.
        case tooLarge(Int)
        /// Dimensions absent, zero, or implausibly large.
        case badDimensions(width: Int, height: Int)
    }

    /// A validated, stripped image.
    public struct SanitizedImage: Sendable {
        /// The stripped bytes.
        public let bytes: Data
        /// The type determined from magic bytes.
        public let type: ImageType
        /// Decoded width.
        public let width: Int
        /// Decoded height.
        public let height: Int
    }

    /// The largest profile image accepted, from the Sec. 23 register.
    public static let maxImageBytes = LumosConstants.fullImageMaxMBDefault * 1024 * 1024

    /// The largest dimension accepted.
    ///
    /// A named constant with rationale, which TRS-CODE-005 permits: the
    /// register bounds image BYTES but not pixels, and a decompression bomb is
    /// a small file declaring enormous dimensions. 8192 is above any real phone
    /// camera's output and far below a size that would exhaust memory.
    public static let maxDimensionPixels = 8192

    /// Validates an image and strips every metadata container from it.
    /// - Parameter raw: the bytes as supplied
    /// - Returns: the validated, stripped image
    /// - Throws: ``Rejection`` when the image fails any SRS-PRO-006 check
    public static func sanitize(_ raw: Data) throws -> SanitizedImage {
        guard raw.count <= maxImageBytes else { throw Rejection.tooLarge(raw.count) }
        let type = try detectType(raw)
        let stripped = type == .jpeg ? try stripJpeg(raw) : try stripPng(raw)
        let size = type == .jpeg ? try jpegDimensions(stripped) : try pngDimensions(stripped)
        guard size.width > 0, size.height > 0,
              size.width <= maxDimensionPixels, size.height <= maxDimensionPixels else {
            throw Rejection.badDimensions(width: size.width, height: size.height)
        }
        return SanitizedImage(bytes: stripped, type: type, width: size.width, height: size.height)
    }

    /// Determines the type from magic bytes, never from a filename or a claim.
    /// - Parameter raw: the bytes
    /// - Returns: the type
    /// - Throws: ``Rejection/unsupportedType``
    public static func detectType(_ raw: Data) throws -> ImageType {
        let bytes = [UInt8](raw.prefix(8))
        if bytes.count >= 3, bytes[0] == 0xFF, bytes[1] == 0xD8, bytes[2] == 0xFF {
            return .jpeg
        }
        if bytes.count >= 8, Array(bytes[0..<8]) == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] {
            return .png
        }
        throw Rejection.unsupportedType
    }

    /// Removes every metadata-bearing JPEG segment.
    ///
    /// Dropped: APP1 (EXIF and XMP), APP2 through APP15, and COM comments.
    /// Kept: APP0 (JFIF density) and everything from SOF onward. ICC profiles
    /// ride in APP2 and go with it - a colour-profile loss is a visible cost,
    /// and it is the right trade against a segment that has carried camera
    /// serial numbers in the wild.
    /// - Parameter raw: the JPEG bytes
    /// - Returns: the stripped JPEG
    /// - Throws: ``Rejection/malformed(_:)``
    public static func stripJpeg(_ raw: Data) throws -> Data {
        let bytes = [UInt8](raw)
        var out: [UInt8] = [0xFF, 0xD8]
        var at = 2

        while at + 3 < bytes.count {
            guard bytes[at] == 0xFF else { throw Rejection.malformed("expected a marker") }
            let marker = bytes[at + 1]
            if marker == 0xD9 { break }
            if marker == 0xDA {
                // Start of scan: the rest is entropy-coded data, copied whole.
                out.append(contentsOf: bytes[at...])
                return Data(out)
            }
            let length = Int(bytes[at + 2]) << 8 | Int(bytes[at + 3])
            guard length >= 2, at + 2 + length <= bytes.count else {
                throw Rejection.malformed("segment length out of range")
            }
            let isApplication = marker >= 0xE1 && marker <= 0xEF
            let isComment = marker == 0xFE
            if !isApplication && !isComment {
                out.append(contentsOf: bytes[at..<(at + 2 + length)])
            }
            at += 2 + length
        }
        out.append(contentsOf: [0xFF, 0xD9])
        return Data(out)
    }

    /// Removes every ancillary PNG chunk that can carry metadata.
    ///
    /// PNG chunk names are case-sensitive: a lower-case first letter marks an
    /// ancillary chunk. Everything ancillary except transparency and gamma is
    /// dropped, covering tEXt, zTXt, iTXt (where XMP lives), eXIf and tIME.
    /// - Parameter raw: the PNG bytes
    /// - Returns: the stripped PNG
    /// - Throws: ``Rejection/malformed(_:)``
    public static func stripPng(_ raw: Data) throws -> Data {
        let bytes = [UInt8](raw)
        var out = Array(bytes[0..<8])
        var at = 8

        while at + 8 <= bytes.count {
            let length = readInt(bytes, at)
            guard length >= 0, at + 12 + length <= bytes.count else {
                throw Rejection.malformed("chunk length out of range")
            }
            let name = String(bytes: bytes[(at + 4)..<(at + 8)], encoding: .ascii) ?? ""
            let critical = name.first.map { $0.isUppercase } ?? false
            if critical || name == "tRNS" || name == "gAMA" {
                out.append(contentsOf: bytes[at..<(at + 12 + length)])
            }
            at += 12 + length
            if name == "IEND" { break }
        }
        return Data(out)
    }

    /// Reads a JPEG's dimensions from its first SOF segment.
    /// - Parameter jpeg: the JPEG bytes
    /// - Returns: the dimensions
    /// - Throws: ``Rejection/malformed(_:)``
    public static func jpegDimensions(_ jpeg: Data) throws -> (width: Int, height: Int) {
        let bytes = [UInt8](jpeg)
        var at = 2
        while at + 9 < bytes.count {
            guard bytes[at] == 0xFF else { throw Rejection.malformed("expected a marker") }
            let marker = bytes[at + 1]
            let length = Int(bytes[at + 2]) << 8 | Int(bytes[at + 3])
            let startOfFrame = marker >= 0xC0 && marker <= 0xCF
                && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if startOfFrame {
                let height = Int(bytes[at + 5]) << 8 | Int(bytes[at + 6])
                let width = Int(bytes[at + 7]) << 8 | Int(bytes[at + 8])
                return (width, height)
            }
            if marker == 0xDA || length < 2 { break }
            at += 2 + length
        }
        throw Rejection.malformed("no frame header")
    }

    /// Reads a PNG's dimensions from its IHDR chunk.
    /// - Parameter png: the PNG bytes
    /// - Returns: the dimensions
    /// - Throws: ``Rejection/malformed(_:)``
    public static func pngDimensions(_ png: Data) throws -> (width: Int, height: Int) {
        let bytes = [UInt8](png)
        guard bytes.count >= 24,
              String(bytes: bytes[12..<16], encoding: .ascii) == "IHDR" else {
            throw Rejection.malformed("no IHDR chunk")
        }
        return (readInt(bytes, 16), readInt(bytes, 20))
    }

    /// Decodes and re-encodes an image, discarding every container property.
    ///
    /// The second half of the pipeline, and the half a segment strip cannot do:
    /// re-encoding destroys anything hidden in the entropy-coded data and
    /// normalises an unusual container. ImageIO is asked for a destination with
    /// NO metadata, so the output carries only what the encoder writes.
    ///
    /// Available on macOS as well as iOS, which is why CI executes it.
    /// - Parameters:
    ///   - image: an already-stripped image
    ///   - quality: JPEG quality, ignored for PNG
    /// - Returns: the re-encoded bytes
    /// - Throws: ``Rejection/malformed(_:)`` when the platform cannot decode it
    public static func reencode(_ image: SanitizedImage, quality: Double = 0.9) throws -> Data {
        guard let source = CGImageSourceCreateWithData(image.bytes as CFData, nil),
              let decoded = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw Rejection.malformed("the platform decoder rejected the image")
        }
        let output = NSMutableData()
        let type = image.type == .jpeg ? UTType.jpeg.identifier : UTType.png.identifier
        guard let destination = CGImageDestinationCreateWithData(
            output as CFMutableData, type as CFString, 1, nil) else {
            throw Rejection.malformed("no encoder for this type")
        }
        // Only the compression setting; no metadata dictionary is supplied, so
        // nothing from the source can be carried across.
        let properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(destination, decoded, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            throw Rejection.malformed("the platform encoder failed")
        }
        return output as Data
    }

    /// Reads a big-endian 32-bit integer.
    /// - Parameters:
    ///   - bytes: the buffer
    ///   - at: the offset
    /// - Returns: the value
    private static func readInt(_ bytes: [UInt8], _ at: Int) -> Int {
        Int(bytes[at]) << 24 | Int(bytes[at + 1]) << 16 | Int(bytes[at + 2]) << 8 | Int(bytes[at + 3])
    }
}

/// Lumos - ios-app/shell / The strip and the re-encode, proven on dirty images.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-005, SRS-PRO-006, SRS-NFR-PRI-008
/// ADRs: ADR-004
/// Threading: any
/// Notes: The fixtures are BUILT IN CODE, mirroring DirtyImages.java byte for
/// byte, so both platforms are proven against identical planted metadata. A
/// committed binary would be a blob nobody can read in a diff; assembled here,
/// every planted byte is visible in the source.
///
/// The planted strings are named for SRS-PRO-005's own three categories -
/// location, device, timestamp - so an assertion of their absence is an
/// assertion of the requirement's words rather than a proxy.
///
/// The re-encode tests are the ones Android's JVM suite cannot have: ImageIO is
/// on macOS, so CI genuinely decodes and re-encodes here.

import CoreGraphics
import Foundation
import ImageIO
import LumosProtocolGenerated
import UniformTypeIdentifiers
import XCTest
@testable import LumosAppShell

/// Builds fixtures carrying known metadata.
enum DirtyImages {
    /// The GPS string planted in EXIF; the SRS-PRO-005 location check.
    static let plantedLocation = "GPS 51.5074N 0.1278W SECRET-LOCATION"
    /// The camera identity planted in EXIF; the device check.
    static let plantedDevice = "Make=ACME Model=PHONE-9 Serial=SECRET-DEVICE"
    /// The capture time planted in EXIF; the timestamp check.
    static let plantedTimestamp = "2026:08:14 09:15:00 SECRET-TIMESTAMP"
    /// An XMP payload, which rides in APP1 alongside EXIF.
    static let plantedXmp = "<x:xmpmeta>SECRET-XMP</x:xmpmeta>"
    /// A JPEG comment segment.
    static let plantedComment = "SECRET-COMMENT"
    /// Text planted in PNG ancillary chunks.
    static let plantedPngText = "SECRET-PNG-TEXT"

    /// Every string the sanitiser must remove.
    static var plantedSecrets: [String] {
        [plantedLocation, plantedDevice, plantedTimestamp, plantedXmp, plantedComment, plantedPngText]
    }

    /// A structurally valid JPEG carrying EXIF, XMP and a comment.
    /// - Parameters:
    ///   - width: the frame width written into SOF0
    ///   - height: the frame height written into SOF0
    /// - Returns: the dirty JPEG
    static func jpegWithEverything(width: Int, height: Int) -> Data {
        var out = Data([0xFF, 0xD8])
        appendSegment(&out, marker: 0xE0, body: Data("JFIF\0".utf8) + Data([1, 2, 0, 0, 1, 0, 1, 0, 0]))
        var exif = Data("Exif\0\0".utf8) + Data("MM\0*".utf8)
        exif += Data(plantedLocation.utf8) + Data([0])
        exif += Data(plantedDevice.utf8) + Data([0])
        exif += Data(plantedTimestamp.utf8) + Data([0])
        appendSegment(&out, marker: 0xE1, body: exif)
        // A SECOND APP1: a stripper that stopped at the first would miss it.
        appendSegment(&out, marker: 0xE1,
                      body: Data("http://ns.adobe.com/xap/1.0/\0".utf8) + Data(plantedXmp.utf8))
        appendSegment(&out, marker: 0xFE, body: Data(plantedComment.utf8))
        appendSegment(&out, marker: 0xC0, body: Data([
            8,
            UInt8((height >> 8) & 0xFF), UInt8(height & 0xFF),
            UInt8((width >> 8) & 0xFF), UInt8(width & 0xFF),
            1, 1, 0x11, 0
        ]))
        appendSegment(&out, marker: 0xDA, body: Data([1, 1, 0, 0, 63, 0]))
        out.append(contentsOf: [0x12, 0x34, 0xFF, 0xD9])
        return out
    }

    /// A structurally valid PNG carrying tEXt, iTXt, eXIf and tIME chunks.
    /// - Parameters:
    ///   - width: the width written into IHDR
    ///   - height: the height written into IHDR
    /// - Returns: the dirty PNG
    static func pngWithEverything(width: Int, height: Int) -> Data {
        var out = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        var ihdr = Data()
        appendInt(&ihdr, width)
        appendInt(&ihdr, height)
        ihdr.append(contentsOf: [8, 6, 0, 0, 0])
        appendChunk(&out, name: "IHDR", body: ihdr)
        appendChunk(&out, name: "tEXt", body: Data("Comment\0".utf8) + Data(plantedPngText.utf8))
        appendChunk(&out, name: "iTXt",
                    body: Data("XML:com.adobe.xmp\0\0\0\0\0".utf8) + Data(plantedXmp.utf8))
        appendChunk(&out, name: "eXIf",
                    body: Data(plantedLocation.utf8) + Data([0]) + Data(plantedDevice.utf8))
        appendChunk(&out, name: "tIME", body: Data([7, 0xEA, 8, 14, 9, 15, 0]))
        appendChunk(&out, name: "IDAT", body: Data([0x78, 0x9C, 0x03, 0, 0, 0, 0, 1]))
        appendChunk(&out, name: "IEND", body: Data())
        return out
    }

    private static func appendSegment(_ out: inout Data, marker: UInt8, body: Data) {
        out.append(contentsOf: [0xFF, marker])
        let length = body.count + 2
        out.append(contentsOf: [UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)])
        out.append(body)
    }

    private static func appendChunk(_ out: inout Data, name: String, body: Data) {
        appendInt(&out, body.count)
        let nameBytes = Data(name.utf8)
        out.append(nameBytes)
        out.append(body)
        appendInt(&out, Int(crc32(nameBytes + body)))
    }

    private static func appendInt(_ out: inout Data, _ value: Int) {
        out.append(contentsOf: [
            UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)
        ])
    }

    /// CRC-32, written out so the fixture needs no dependency.
    private static func crc32(_ data: Data) -> UInt32 {
        var table = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1
            }
            table[i] = c
        }
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}

/// The metadata strip, the validations, and the platform re-encode.
final class MediaSanitizerTests: XCTestCase {

    func testTheFixtureActuallyContainsTheMetadata() {
        // Without this, a stripper that did nothing would pass every test below
        // simply because the fixture never carried anything.
        let jpeg = DirtyImages.jpegWithEverything(width: 640, height: 480)
        let png = DirtyImages.pngWithEverything(width: 64, height: 48)
        for secret in [DirtyImages.plantedLocation, DirtyImages.plantedDevice,
                       DirtyImages.plantedTimestamp, DirtyImages.plantedComment] {
            XCTAssertNotNil(jpeg.range(of: Data(secret.utf8)), "fixture must plant \(secret)")
        }
        XCTAssertNotNil(png.range(of: Data(DirtyImages.plantedPngText.utf8)))
        XCTAssertNotNil(png.range(of: Data(DirtyImages.plantedLocation.utf8)))
    }

    func testJpegStripRemovesEverySecret() throws {
        let clean = try MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(width: 640, height: 480))
        for secret in DirtyImages.plantedSecrets {
            XCTAssertNil(clean.bytes.range(of: Data(secret.utf8)),
                         "prohibited metadata survived the strip: \(secret)")
        }
    }

    func testPngStripRemovesEverySecret() throws {
        let clean = try MediaSanitizer.sanitize(DirtyImages.pngWithEverything(width: 64, height: 48))
        for secret in DirtyImages.plantedSecrets {
            XCTAssertNil(clean.bytes.range(of: Data(secret.utf8)),
                         "prohibited metadata survived the strip: \(secret)")
        }
    }

    func testTheStrippedImageIsStillTheSameImage() throws {
        let clean = try MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(width: 640, height: 480))
        XCTAssertEqual(clean.type, .jpeg)
        XCTAssertEqual(clean.width, 640, "stripping must not disturb the frame header")
        XCTAssertEqual(clean.height, 480)
        XCTAssertNotNil(clean.bytes.range(of: Data([0x12, 0x34])), "the image data itself must survive")
    }

    func testStrippingIsIdempotent() throws {
        let once = try MediaSanitizer.sanitize(DirtyImages.jpegWithEverything(width: 64, height: 48)).bytes
        let twice = try MediaSanitizer.sanitize(once).bytes
        XCTAssertEqual(once, twice, "a second pass must find nothing left to remove")
    }

    func testPngKeepsCriticalChunksAndDropsMetadataOnes() throws {
        let clean = try MediaSanitizer.sanitize(DirtyImages.pngWithEverything(width: 64, height: 48)).bytes
        for chunk in ["IHDR", "IDAT", "IEND"] {
            XCTAssertNotNil(clean.range(of: Data(chunk.utf8)), "\(chunk) is critical and must survive")
        }
        for chunk in ["tEXt", "iTXt", "eXIf", "tIME"] {
            XCTAssertNil(clean.range(of: Data(chunk.utf8)), "\(chunk) carries metadata and must not survive")
        }
    }

    // MARK: SRS-PRO-006 validation

    func testTypeComesFromMagicBytesNotFromAClaim() {
        XCTAssertThrowsError(try MediaSanitizer.sanitize(Data("GIF89a not a jpeg".utf8))) { error in
            XCTAssertEqual(error as? MediaSanitizer.Rejection, .unsupportedType)
        }
    }

    func testAnOversizedImageIsRejected() {
        let huge = Data(count: MediaSanitizer.maxImageBytes + 1)
        XCTAssertThrowsError(try MediaSanitizer.sanitize(huge)) { error in
            guard case MediaSanitizer.Rejection.tooLarge = error else {
                return XCTFail("expected tooLarge, got \(error)")
            }
        }
    }

    func testADecompressionBombIsRejectedOnDimensions() {
        let bomb = DirtyImages.pngWithEverything(
            width: MediaSanitizer.maxDimensionPixels + 1, height: 64)
        XCTAssertThrowsError(try MediaSanitizer.sanitize(bomb)) { error in
            guard case MediaSanitizer.Rejection.badDimensions = error else {
                return XCTFail("a small file declaring enormous dimensions is the bomb shape")
            }
        }
    }

    func testATruncatedImageIsRejectedRatherThanReadPastTheEnd() {
        let whole = DirtyImages.jpegWithEverything(width: 64, height: 48)
        let truncated = whole.prefix(whole.count / 2)
        XCTAssertThrowsError(try MediaSanitizer.sanitize(Data(truncated)))
    }

    func testTheSizeBoundComesFromTheRegister() {
        XCTAssertEqual(MediaSanitizer.maxImageBytes,
                       LumosConstants.fullImageMaxMBDefault * 1024 * 1024,
                       "ADR-004: the bound is the register's, not a literal")
    }

    // MARK: re-encode, which only a platform codec can do

    func testReencodeProducesADecodableImageWithNoMetadata() throws {
        // A real image the platform can decode: encoded by ImageIO, then made
        // dirty by prepending a real EXIF segment with our own writer.
        let real = try realJpeg(width: 32, height: 24)
        let dirty = try injectExifSegment(into: real)
        XCTAssertNotNil(dirty.range(of: Data(DirtyImages.plantedLocation.utf8)),
                        "the fixture must be genuinely dirty before the pipeline runs")

        let stripped = try MediaSanitizer.sanitize(dirty)
        let reencoded = try MediaSanitizer.reencode(stripped)

        XCTAssertNil(reencoded.range(of: Data(DirtyImages.plantedLocation.utf8)))
        XCTAssertNotNil(CGImageSourceCreateWithData(reencoded as CFData, nil),
                        "the re-encoded bytes must still be a decodable image")

        // And the platform's own metadata reader must find nothing identifying.
        let source = CGImageSourceCreateWithData(reencoded as CFData, nil)!
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary], "no GPS block may survive")
        XCTAssertNil(properties[kCGImagePropertyExifDictionary], "no EXIF block may survive")
    }

    /// Encodes a real, decodable JPEG using the platform codec.
    private func realJpeg(width: Int, height: Int) throws -> Data {
        let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        context.setFillColor(CGColor(red: 0.2, green: 0.4, blue: 0.6, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = context.makeImage()!
        let out = NSMutableData()
        let destination = CGImageDestinationCreateWithData(
            out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return out as Data
    }

    /// Prepends an APP1/EXIF segment carrying the planted location.
    private func injectExifSegment(into jpeg: Data) throws -> Data {
        var body = Data("Exif\0\0".utf8) + Data("MM\0*".utf8)
        body += Data(DirtyImages.plantedLocation.utf8) + Data([0])
        var out = Data(jpeg.prefix(2))
        let length = body.count + 2
        out.append(contentsOf: [0xFF, 0xE1, UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)])
        out.append(body)
        out.append(jpeg.dropFirst(2))
        return out
    }
}

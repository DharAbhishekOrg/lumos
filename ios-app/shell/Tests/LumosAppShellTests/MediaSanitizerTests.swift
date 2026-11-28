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
        guard let source = CGImageSourceCreateWithData(reencoded as CFData, nil) else {
            return XCTFail("the re-encoded bytes must still be a decodable image")
        }

        // And the platform's own metadata reader must find nothing IDENTIFYING.
        //
        // Not "no EXIF dictionary at all": ImageIO always writes ColorSpace,
        // PixelXDimension and PixelYDimension for a JPEG, and those are
        // rendering facts rather than metadata about the photographer. The
        // assertion is SRS-PRO-005's own three categories - location, device,
        // timestamp - which is what the requirement actually forbids. An
        // earlier version of this test demanded the dictionary be absent
        // entirely and failed against a platform that will never do that.
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary], "location: no GPS block may survive")

        let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        for deviceTag in [kCGImagePropertyTIFFMake, kCGImagePropertyTIFFModel,
                          kCGImagePropertyTIFFDateTime] {
            XCTAssertNil(tiff[deviceTag], "device/timestamp tag \(deviceTag) must not survive")
        }

        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        for identifyingTag in [kCGImagePropertyExifDateTimeOriginal,
                               kCGImagePropertyExifDateTimeDigitized,
                               kCGImagePropertyExifLensMake,
                               kCGImagePropertyExifLensModel,
                               kCGImagePropertyExifBodySerialNumber] {
            XCTAssertNil(exif[identifyingTag], "identifying tag \(identifyingTag) must not survive")
        }
    }

    func testThePhotoReencoderStandsASidewaysCameraPhotoUpAndDropsItsMetadata() throws {
        // A camera-sized picture saved sideways (EXIF orientation 6) with a
        // location, a device and a time, all written by ImageIO itself.
        let width = 3200
        let height = 2400
        let context = try XCTUnwrap(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                                              bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        context.setFillColor(CGColor(red: 0.8, green: 0.5, blue: 0.3, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let out = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            out as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil))
        let planted: [CFString: Any] = [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 51.5, kCGImagePropertyGPSLatitudeRef: "N"],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "SECRET-MAKE",
                                             kCGImagePropertyTIFFModel: "SECRET-MODEL",
                                             kCGImagePropertyTIFFDateTime: "2026:08:14 09:15:00"]
        ]
        CGImageDestinationAddImage(destination, image, planted as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let sideways = out as Data
        XCTAssertNotNil(sideways.range(of: Data("SECRET-MAKE".utf8)), "the source must be genuinely dirty")

        let redrawn = try ImageIOPhotoReencoder().reencode(sideways)

        let source = try XCTUnwrap(CGImageSourceCreateWithData(redrawn as CFData, nil))
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        XCTAssertEqual(properties[kCGImagePropertyPixelWidth] as? Int, 1200, "stood up: portrait")
        XCTAssertEqual(properties[kCGImagePropertyPixelHeight] as? Int, ImageIOPhotoReencoder.longEdgePixels)
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary])
        let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        XCTAssertNil(tiff[kCGImagePropertyTIFFMake])
        XCTAssertNil(tiff[kCGImagePropertyTIFFModel])
        XCTAssertNil(tiff[kCGImagePropertyTIFFDateTime])
        XCTAssertNil(redrawn.range(of: Data("SECRET-MAKE".utf8)))
        let clean = try MediaSanitizer.sanitize(redrawn)
        XCTAssertEqual(clean.type, .jpeg)
        XCTAssertEqual(clean.width, 1200)
    }

    func testThePhotoReencoderRefusesBytesThatAreNoPicture() {
        XCTAssertThrowsError(try ImageIOPhotoReencoder().reencode(Data("<svg onload=alert(1)>".utf8)))
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

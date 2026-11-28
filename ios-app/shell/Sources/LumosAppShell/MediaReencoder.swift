/// Lumos - ios-app/shell / ImageIO re-encoding: the sanitiser's second half, and the profile-photo re-draw.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-005, SRS-PRO-006, TRS-MED-002
/// ADRs: ADR-004
/// Threading: any background thread (decoding is slow and allocates)
/// Notes: Split out of MediaSanitizer.swift so the byte-level strip builds and
/// tests everywhere; this file needs ImageIO (iOS and macOS, and CI runs it).
/// `MediaSanitizer.reencode` is unchanged. `ImageIOPhotoReencoder` is the
/// iPhone's PhotoReencoder, the twin of Android's BitmapReencoder: the picked
/// bytes are capped before decoding, the header is read alone and a picture
/// claiming more than 200 MP is refused, then ImageIO draws a thumbnail no
/// larger than 1600 px on the long edge WITH the orientation applied (so
/// photos stand up, HEIC included), and encodes it as JPEG at 0.85, stepping
/// down until it fits FULL_IMAGE_MAX_MB. No metadata dictionary is passed to
/// the encoder, so nothing from the source is carried across.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

extension MediaSanitizer {

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
        let type = image.type == .jpeg ? UTType.jpeg.identifier : UTType.png.identifier
        return try encode(decoded, type: type, quality: quality)
    }

    /// Encodes one image with no metadata dictionary.
    static func encode(_ image: CGImage, type: String, quality: Double) throws -> Data {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output as CFMutableData, type as CFString, 1, nil) else {
            throw Rejection.malformed("no encoder for this type")
        }
        // Only the compression setting; no metadata dictionary is supplied, so
        // nothing from the source can be carried across.
        let properties: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: quality]
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else {
            throw Rejection.malformed("the platform encoder failed")
        }
        return output as Data
    }
}

/// The iPhone's profile-photo re-encoder.
public struct ImageIOPhotoReencoder: PhotoReencoder {

    /// The longest edge a profile photo keeps (as Android).
    public static let longEdgePixels = 1600
    /// The most pixels a source may claim in its header (200 MP, the largest phone sensors).
    public static let maxSourcePixels = 200_000_000
    /// The most bytes taken from the picker; a larger file is refused undecoded.
    public static let maxSourceBytes = 64 * 1024 * 1024
    /// JPEG qualities tried in turn until the output fits.
    private static let qualities = [0.85, 0.75, 0.65, 0.55]

    public init() {}

    public func reencode(_ picked: Data) throws -> Data {
        guard picked.count <= Self.maxSourceBytes else {
            throw MediaSanitizer.Rejection.tooLarge(picked.count)
        }
        guard let source = CGImageSourceCreateWithData(picked as CFData, nil),
              let header = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = header[kCGImagePropertyPixelWidth] as? Int,
              let height = header[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width * height <= Self.maxSourcePixels else {
            throw MediaSanitizer.Rejection.badDimensions(width: 0, height: 0)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.longEdgePixels,
            kCGImageSourceShouldCacheImmediately: true
        ]
        guard let upright = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            throw MediaSanitizer.Rejection.malformed("the picture did not decode")
        }
        for quality in Self.qualities {
            let out = try MediaSanitizer.encode(upright, type: UTType.jpeg.identifier, quality: quality)
            if out.count <= MediaSanitizer.maxImageBytes {
                return out
            }
        }
        throw MediaSanitizer.Rejection.tooLarge(MediaSanitizer.maxImageBytes)
    }
}

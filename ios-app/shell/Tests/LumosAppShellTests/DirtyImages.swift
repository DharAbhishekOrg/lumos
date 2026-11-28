/// Lumos - ios-app/shell / The known-dirty image fixtures, shared by the sanitiser and photo tests.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-PRO-005, SRS-NFR-PRI-008, TRS-TST-001
/// ADRs: ADR-004
/// Threading: test-only
/// Notes: Moved out of MediaSanitizerTests.swift unchanged, so tests that do
/// not need ImageIO (ProfilePhotosTests) can use them on every platform. They
/// mirror DirtyImages.java byte for byte.

import Foundation

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

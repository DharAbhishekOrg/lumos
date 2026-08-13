/// Lumos - ios-app/shell / Marking store files do-not-back-up.
/// Author: dhar.abhishek.email@gmail.com
/// Implements: SRS-DAT-012, TRS-LOC-009, TRS-IOS-007
/// ADRs: ADR-003
/// Threading: any
/// Notes: The iOS half of ADR-003's backup exclusion. Keychain items already
/// carry ThisDeviceOnly (KeychainBlobStore); this is the file half - the
/// database, its journals and the object store must not enter an iCloud or
/// iTunes backup (threat model LT-20/LT-21).
///
/// isExcludedFromBackup is a per-URL resource value, so it has to be applied to
/// every file the store creates and RE-applied to files SQLite creates behind
/// our back: a `-wal` and a `-shm` appear the first time the database is
/// written in WAL mode, and a database excluded from backup whose write-ahead
/// log is not is a database whose most recent writes are in the backup.
/// `excludeDatabaseFamily` exists for exactly that reason.
///
/// Unlike most platform behaviour, this one IS verifiable in CI: the resource
/// value round-trips on macOS, so the test asserts what was set rather than
/// asserting that an API was called.

import Foundation

/// Applies and reads the do-not-back-up attribute.
public enum BackupExclusion {

    /// The suffixes SQLite adds to a database file.
    ///
    /// Both are written beside the database and both can contain committed
    /// rows, so both are excluded alongside it.
    public static let sqliteSidecarSuffixes = ["-wal", "-shm", "-journal"]

    /// Marks a single file as excluded from OS backups.
    /// - Parameter url: the file, which must already exist
    /// - Throws: whatever the filesystem reports
    public static func exclude(url: URL) throws {
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try target.setResourceValues(values)
    }

    /// Reads back the do-not-back-up attribute.
    /// - Parameter url: the file
    /// - Returns: true when the file is excluded
    public static func isExcluded(url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
    }

    /// Excludes a database and every SQLite sidecar that exists beside it.
    ///
    /// Call after opening AND after the first write: the sidecars do not exist
    /// until SQLite creates them, and a file that does not exist cannot carry a
    /// resource value.
    /// - Parameter databaseURL: the main database file
    /// - Throws: whatever the filesystem reports for the main file; a missing
    ///   sidecar is not an error, because most of the time there isn't one
    public static func excludeDatabaseFamily(databaseURL: URL) throws {
        try exclude(url: databaseURL)
        for suffix in sqliteSidecarSuffixes {
            let sidecar = URL(fileURLWithPath: databaseURL.path + suffix)
            if FileManager.default.fileExists(atPath: sidecar.path) {
                try exclude(url: sidecar)
            }
        }
    }
}

/*
 * Lumos - android-app/app / The encrypted profile-media object store.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-004, SRS-DAT-002, SRS-NFR-SEC-013
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: ProfileMedia has said since WP-02 that "the bytes live in the
 * encrypted object store"; this is that store, completing SRS-PRO-004. Each
 * object is one file: version byte, random 12-byte IV, AES-256-GCM
 * ciphertext under a key HKDF-derived from the S1 MEDIA_STORE domain key -
 * the same construction as SealedRecordCodec, one domain over. The AAD binds
 * the object identifier and format version, so a file renamed or copied over
 * another fails exactly as a tampered one does (the LT-10 relocation attack,
 * applied to files). Identifiers are random, never derived from content:
 * a content-derived name would leak whether two profiles share a photo.
 *
 * On-device-until-shared (SRS-PRO-004's second half) is structural: this
 * store has no export, no URI provider and no plaintext path - bytes leave
 * only through get(), whose callers are the rendering and the consented
 * sharing flows. Crypto-erasure covers it on wipe: the KEK dies first
 * (KeysFirstWipe), the domain key dies with it, and every object here is
 * ciphertext under a key that no longer exists; wipeAll() then deletes the
 * unreadable files as hygiene.
 */

package com.lumos.app.media;

import com.lumos.app.crypto.Hkdf;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Stores profile media encrypted at rest (SRS-PRO-004). */
public final class MediaObjectStore {

    /** HKDF context for the object-encryption key. */
    public static final String OBJECT_KEY_INFO = "lumos/local/media-object-key/v1";

    /** The on-disk format version. */
    private static final byte FORMAT_VERSION = 1;

    /** AAD domain prefix binding identifier and version. */
    private static final String AAD_PREFIX = "lumos/media-object/1/";

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int ID_BYTES = 16;

    private final Path directory;
    private final byte[] objectKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * Opens the store in a directory, creating it if needed.
     *
     * @param directory the private directory the objects live in
     * @param mediaDomainKey the S1 MEDIA_STORE domain key; the caller keeps
     *     ownership
     * @throws IOException when the directory cannot be created
     */
    public MediaObjectStore(final Path directory, final byte[] mediaDomainKey)
            throws IOException {
        this.directory = directory;
        this.objectKey = Hkdf.derive(mediaDomainKey, new byte[32],
                OBJECT_KEY_INFO.getBytes(StandardCharsets.UTF_8), 32);
        Files.createDirectories(directory);
    }

    /**
     * Stores one sanitised media object.
     *
     * @param sanitisedBytes the bytes the sanitiser produced - this store
     *     holds what the pipeline approved, never what a file claimed to be
     * @return the random object identifier for the ProfileMedia record
     * @throws GeneralSecurityException on cipher failure
     * @throws IOException on write failure
     */
    public String put(final byte[] sanitisedBytes)
            throws GeneralSecurityException, IOException {
        final byte[] idBytes = new byte[ID_BYTES];
        random.nextBytes(idBytes);
        final String id = hex(idBytes);

        final byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(objectKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD((AAD_PREFIX + id).getBytes(StandardCharsets.UTF_8));
        final byte[] ciphertext = cipher.doFinal(sanitisedBytes);

        final byte[] file = new byte[1 + GCM_IV_BYTES + ciphertext.length];
        file[0] = FORMAT_VERSION;
        System.arraycopy(iv, 0, file, 1, GCM_IV_BYTES);
        System.arraycopy(ciphertext, 0, file, 1 + GCM_IV_BYTES, ciphertext.length);
        Files.write(directory.resolve(id), file);
        return id;
    }

    /**
     * Opens one object.
     *
     * @param id the object identifier
     * @return the plaintext media bytes
     * @throws GeneralSecurityException when the file is missing, tampered,
     *     relocated or from an unknown format - indistinguishably
     * @throws IOException on read failure
     */
    public byte[] get(final String id) throws GeneralSecurityException, IOException {
        final Path path = resolveStrict(id);
        if (!Files.exists(path)) {
            throw new GeneralSecurityException("no such media object");
        }
        final byte[] file = Files.readAllBytes(path);
        if (file.length < 1 + GCM_IV_BYTES + 1 || file[0] != FORMAT_VERSION) {
            throw new GeneralSecurityException("unreadable media object");
        }
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(objectKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOfRange(file, 1, 1 + GCM_IV_BYTES)));
        cipher.updateAAD((AAD_PREFIX + id).getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(Arrays.copyOfRange(file, 1 + GCM_IV_BYTES, file.length));
    }

    /**
     * Deletes one object; absent is success.
     *
     * @param id the object identifier
     * @throws IOException on filesystem failure
     * @throws GeneralSecurityException when the identifier is not one of ours
     */
    public void delete(final String id) throws IOException, GeneralSecurityException {
        Files.deleteIfExists(resolveStrict(id));
    }

    /**
     * Lists the stored object identifiers.
     *
     * @return the identifiers, in no particular order
     * @throws IOException on filesystem failure
     */
    public List<String> list() throws IOException {
        final List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (final Path path : stream) {
                final Path name = path.getFileName();
                if (name != null) {
                    out.add(name.toString());
                }
            }
        }
        return out;
    }

    /**
     * Deletes every object - the wipe flow's content pass, after crypto
     * erasure has already made each file unreadable.
     *
     * @return how many files were deleted
     * @throws IOException on filesystem failure
     */
    public int wipeAll() throws IOException {
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (final Path path : stream) {
                Files.deleteIfExists(path);
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Resolves an identifier to its file, refusing anything that is not a
     * plain hex identifier - a path-shaped id must never escape the store
     * directory.
     */
    private Path resolveStrict(final String id) throws GeneralSecurityException {
        if (id.length() != ID_BYTES * 2 || !id.chars().allMatch(
                c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new GeneralSecurityException("not a media object identifier");
        }
        return directory.resolve(id);
    }

    private static String hex(final byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}

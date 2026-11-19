/*
 * Lumos - android-app/app / Media encrypted at rest, executed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-004, SRS-DAT-002, TRS-TST-001
 * ADRs: ADR-001, ADR-003
 * Threading: test-only
 * Notes: SRS-PRO-004 over a real filesystem: a stored object round-trips, the
 * on-disk file contains none of the plaintext (encrypted at rest as an
 * executed property, not a word), a tampered byte and the LT-10 relocation
 * (one object's file copied over another's name) fail identically, a
 * path-shaped identifier cannot escape the directory, and wipeAll leaves
 * nothing behind.
 */

package com.lumos.app.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.keystore.SoftwareKekStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** MediaObjectStore, held to its at-rest promises. */
final class MediaObjectStoreTest {

    private static byte[] mediaKey() throws Exception {
        return DataKeyHierarchy.create(SoftwareKekStore.create())
                .deriveKey(DataKeyHierarchy.DataDomain.MEDIA_STORE);
    }

    private static byte[] samplePhoto() {
        // Recognisable plaintext the at-rest check can scan for.
        return "SANITISED-JPEG-BYTES-the-sanitiser-produced-1234567890"
                .repeat(20).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anObjectRoundTripsAndItsFileHoldsNoPlaintext(@TempDir final Path dir) throws Exception {
        final MediaObjectStore store = new MediaObjectStore(dir, mediaKey());
        final byte[] photo = samplePhoto();
        final String id = store.put(photo);

        assertArrayEquals(photo, store.get(id));

        final byte[] onDisk = Files.readAllBytes(dir.resolve(id));
        final String disk = new String(onDisk, StandardCharsets.ISO_8859_1);
        assertFalse(disk.contains("SANITISED-JPEG-BYTES"),
                "encrypted at rest means the file carries no plaintext (SRS-PRO-004)");
    }

    @Test
    void aTamperedByteFailsClosed(@TempDir final Path dir) throws Exception {
        final MediaObjectStore store = new MediaObjectStore(dir, mediaKey());
        final String id = store.put(samplePhoto());
        final byte[] file = Files.readAllBytes(dir.resolve(id));
        file[file.length - 1] ^= 0x01;
        Files.write(dir.resolve(id), file);
        assertThrows(GeneralSecurityException.class, () -> store.get(id));
    }

    @Test
    void aRelocatedObjectFailsLikeATamperedOne(@TempDir final Path dir) throws Exception {
        // LT-10 applied to files: copying one object's bytes over another's
        // name must not open, because the AAD binds the identifier.
        final MediaObjectStore store = new MediaObjectStore(dir, mediaKey());
        final String first = store.put(samplePhoto());
        final String second = store.put("second object".getBytes(StandardCharsets.UTF_8));
        Files.copy(dir.resolve(first), dir.resolve(second),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(GeneralSecurityException.class, () -> store.get(second));
    }

    @Test
    void aPathShapedIdentifierCannotEscapeTheStore(@TempDir final Path dir) throws Exception {
        final MediaObjectStore store = new MediaObjectStore(dir.resolve("media"), mediaKey());
        // android.jar has no Files.writeString; write bytes directly.
        Files.write(dir.resolve("outside.txt"), "not yours".getBytes(StandardCharsets.UTF_8));
        assertThrows(GeneralSecurityException.class, () -> store.get("../outside.txt"));
        assertThrows(GeneralSecurityException.class, () -> store.delete("../outside.txt"));
        assertTrue(Files.exists(dir.resolve("outside.txt")));
    }

    @Test
    void aDifferentDomainKeyOpensNothing(@TempDir final Path dir) throws Exception {
        // Crypto-erasure is the wipe story: when the KEK dies the domain key
        // dies, and this is what every stored object then is - unreadable.
        final MediaObjectStore store = new MediaObjectStore(dir, mediaKey());
        final String id = store.put(samplePhoto());
        final MediaObjectStore afterWipe = new MediaObjectStore(dir, mediaKey());
        assertThrows(GeneralSecurityException.class, () -> afterWipe.get(id),
                "a fresh hierarchy's key opens nothing the old one sealed");
    }

    @Test
    void deleteListAndWipeAllBehave(@TempDir final Path dir) throws Exception {
        final MediaObjectStore store = new MediaObjectStore(dir, mediaKey());
        final String a = store.put(samplePhoto());
        final String b = store.put(samplePhoto());
        assertEquals(2, store.list().size());

        store.delete(a);
        assertEquals(java.util.List.of(b), store.list());
        store.delete(a);

        store.put(samplePhoto());
        assertEquals(2, store.wipeAll());
        assertTrue(store.list().isEmpty(), "the content pass leaves nothing behind");
    }
}

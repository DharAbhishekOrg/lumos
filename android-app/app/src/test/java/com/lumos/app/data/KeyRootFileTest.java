/*
 * Lumos - android-app/app / The key root file: round trip, absence, and refusal of anything else.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-002, SRS-DAT-002, TRS-TST-001
 * ADRs: ADR-003, ADR-012
 * Threading: test-only
 * Notes: The boot reads this before it can open anything else, so a corrupt
 * file must be an error the boot can report, never a wrong key.
 */

package com.lumos.app.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lumos.app.keystore.KeyProtection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The persisted key root. */
public class KeyRootFileTest {

    @TempDir
    File dir;

    @Test
    void aRootRoundTripsAndReplacesTheOldOneWhole() throws Exception {
        final File f = new File(dir, KeyRootFile.NAME);
        assertFalse(KeyRootFile.read(f).isPresent(), "no file: onboarding has not run on this install");
        KeyRootFile.write(f, new KeyRootFile.Root(KeyProtection.TEE, new byte[] {1, 2, 3}));
        KeyRootFile.write(f, new KeyRootFile.Root(KeyProtection.STRONGBOX, new byte[] {9, 8}));
        final KeyRootFile.Root back = KeyRootFile.read(f).orElseThrow();
        assertEquals(KeyProtection.STRONGBOX, back.protection());
        assertArrayEquals(new byte[] {9, 8}, back.wrappedRoot());
        assertFalse(new File(dir, KeyRootFile.NAME + ".tmp").exists(), "no temp file is left behind");
    }

    @Test
    void anythingThatIsNotAKeyRootIsAnError() throws Exception {
        final File f = new File(dir, KeyRootFile.NAME);
        for (final byte[] bad : new byte[][] {{}, {1}, {2, 0, 0, 1, 7}, {1, 9, 0, 1, 7}, {1, 0, 0, 2, 7},
            {1, 0, 0, 0}, {1, 0, 0, 1, 7, 7}}) {
            Files.write(f.toPath(), bad);
            assertThrows(IOException.class, () -> KeyRootFile.read(f));
        }
        assertThrows(IOException.class, () -> KeyRootFile.write(f, new KeyRootFile.Root(KeyProtection.TEE, new byte[0])));
    }
}

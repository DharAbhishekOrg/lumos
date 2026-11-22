/*
 * Lumos - android-app/app / The one record the app needs before it can open its database.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-002, SRS-DAT-002, TRS-AND-007
 * ADRs: ADR-003, ADR-010, ADR-012
 * Threading: any
 * Notes: ADR-012. The database is sealed under keys derived from the data
 * root, so the wrapped root cannot live inside it: it lives in this file,
 * beside the KEK's recorded protection tier, which the keystore must be told
 * when the KEK is reopened. Nothing here is secret - the root is wrapped
 * under the hardware KEK, and without the KEK it is noise - but it is also
 * worthless on another device, so it goes in the no-backup directory
 * (TRS-AND-007) and a restore from backup is a fresh install, as ADR-003
 * intends. Writes are atomic (temp file, then rename), so a crash leaves the
 * old file or the new one, never half of either. The format is versioned so
 * a future key-rotation can change it without guessing.
 *
 * Layout: version u8 = 1 | protection u8 (KeyProtection ordinal) |
 * root_len u16 | wrapped_root.
 */

package com.lumos.app.data;

import com.lumos.app.keystore.KeyProtection;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Optional;

/** The persisted key root. */
public final class KeyRootFile {

    /** The file name inside the no-backup directory. */
    public static final String NAME = "lumos-key-root.bin";
    private static final int VERSION = 1;
    private static final int MAX_ROOT = 0xFFFF;
    private static final int CHUNK = 512;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHORT_MASK = 0xFFFF;

    private KeyRootFile() { }

    /**
     * What the file holds.
     *
     * @param protection the KEK's recorded tier
     * @param wrappedRoot the data root wrapped under the KEK
     */
    public record Root(KeyProtection protection, byte[] wrappedRoot) {

        /**
         * Copies.
         *
         * @param protection see the record
         * @param wrappedRoot see the record
         */
        public Root {
            wrappedRoot = wrappedRoot.clone();
        }

        /** @return a copy of the wrapped root */
        @Override
        public byte[] wrappedRoot() {
            return wrappedRoot.clone();
        }
    }

    /**
     * Writes the root atomically.
     *
     * @param file the target file
     * @param root what to write
     * @throws IOException when the write or the rename fails
     */
    public static void write(final File file, final Root root) throws IOException {
        final byte[] wrapped = root.wrappedRoot();
        if (wrapped.length == 0 || wrapped.length > MAX_ROOT) {
            throw new IOException("wrapped root has an impossible length");
        }
        final byte[] bytes = ByteBuffer.allocate(4 + wrapped.length).put((byte) VERSION)
                .put((byte) root.protection().ordinal()).putShort((short) wrapped.length).put(wrapped).array();
        final File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("could not move the key root into place");
        }
    }

    /**
     * Reads the root, if onboarding has written one on this install.
     *
     * @param file the file
     * @return the root, or empty when there is no file
     * @throws IOException when the file exists but cannot be read or is not a key root
     */
    public static Optional<Root> read(final File file) throws IOException {
        if (!file.exists()) {
            return Optional.empty();
        }
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            final byte[] chunk = new byte[CHUNK];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                raw.write(chunk, 0, n);
            }
        }
        try {
            final ByteBuffer b = ByteBuffer.wrap(raw.toByteArray());
            if ((b.get() & BYTE_MASK) != VERSION) {
                throw new IOException("unknown key root version");
            }
            final int p = b.get() & BYTE_MASK;
            final KeyProtection[] tiers = KeyProtection.values();
            if (p >= tiers.length) {
                throw new IOException("unknown key protection tier");
            }
            final int len = b.getShort() & SHORT_MASK;
            if (len == 0 || len != b.remaining()) {
                throw new IOException("key root length is wrong");
            }
            final byte[] wrapped = new byte[len];
            b.get(wrapped);
            return Optional.of(new Root(tiers[p], wrapped));
        } catch (final BufferUnderflowException e) {
            throw new IOException("key root is truncated", e);
        }
    }
}

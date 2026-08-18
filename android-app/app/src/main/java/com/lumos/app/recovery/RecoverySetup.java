/*
 * Lumos - android-app/app / Recovery setup: assembling and sealing an export.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, SRS-NFR-PRI-004
 * ADRs: ADR-003, ADR-010
 * Threading: serial-executor
 * Notes: The flow SRS-ONB-007 describes, over the record format S4b proved.
 * Setup does three things and refuses to do a fourth: it reads the content
 * sections from the store, seals them under a passphrase-derived key, and hands
 * the bytes back to the caller to put wherever the user chose.
 *
 * It never reads IDENTITY_KEY_RECORD, and that is not an oversight to be fixed
 * later. ADR-003 makes identity keys non-exportable and never escrowed, so the
 * export path has no line that touches them and exportedSectionsCarryNoKeys()
 * asserts the bytes of a real identity are absent from a real export.
 *
 * The destination is the CALLER'S problem, deliberately. Threat model LT-25:
 * an export written to shared storage or a cloud drive is High severity, the
 * destination is the user's choice and outside our control, and the UI must say
 * so. This class returns bytes; it does not write a file, so it cannot write
 * one to the wrong place.
 *
 * ADR-010 requires action-level device authentication before an export. That
 * prompt belongs to the presentation layer that calls this - there is no
 * activity in WP-02 to host it - and the trace matrix says so rather than this
 * class pretending to have done it.
 */

package com.lumos.app.recovery;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds a content-only recovery export from the local store. */
public final class RecoverySetup {

    private final LocalStore store;
    private final RecoveryRecord record;
    private final LocalStore.Clock clock;

    /**
     * Assembles the setup flow from its collaborators.
     *
     * @param store the opened local store
     * @param record the S4b record format, over its crypto seam
     * @param clock the UTC clock
     */
    public RecoverySetup(final LocalStore store, final RecoveryRecord record,
            final LocalStore.Clock clock) {
        this.store = store;
        this.record = record;
        this.clock = clock;
    }

    /**
     * Reads every available section out of the store.
     *
     * <p>Exposed separately from {@link #export} so a preview screen can tell
     * the user what is about to be in the file before they choose a passphrase.
     * "What am I backing up?" is a question a privacy-first app should answer
     * before the irreversible part, not after.
     *
     * @return the assembled content
     * @throws StoreException on engine failure or an unopenable row
     */
    public RecoveryBundle assemble() throws StoreException {
        final Map<RecoverySection, List<RecoveryBundle.Item>> sections =
                new EnumMap<>(RecoverySection.class);
        for (final RecoverySection section : RecoverySection.values()) {
            if (!section.isAvailable()) {
                continue;
            }
            final List<RecoveryBundle.Item> items = new ArrayList<>();
            for (final LocalStore.Entry entry : store.allOf(section.kind())) {
                items.add(new RecoveryBundle.Item(entry.id(), entry.content()));
            }
            sections.put(section, items);
        }
        return new RecoveryBundle(clock.nowUtcMillis(), sections);
    }

    /**
     * Assembles and seals an export (SRS-ONB-007, SRS-DAT-004).
     *
     * <p>The passphrase is zeroed on the way out. The caller supplied it as
     * bytes rather than a String for exactly that reason: a String cannot be
     * cleared and would sit in the heap until the collector felt like it.
     *
     * @param passphrase the user's passphrase, UTF-8; zeroed before return
     * @return the sealed record bytes, for the user to store where they choose
     * @throws StoreException on engine failure
     * @throws RecoveryException if derivation or sealing fails
     */
    public byte[] export(final byte[] passphrase) throws StoreException, RecoveryException {
        final byte[] plaintext = RecoveryBundleCodec.encode(assemble());
        try {
            return record.export(passphrase, plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(passphrase, (byte) 0);
        }
    }
}

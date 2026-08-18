/*
 * Lumos - android-app/app / Storing the consent evidence.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-002, SRS-DAT-002
 * ADRs: -
 * Threading: serial-executor
 * Notes: A Repository (TRS Sec. 24.2) over the CONSENT_RECORD table, which is
 * inside the encrypted store - threat model LT-31 asks for exactly that, so a
 * record of what someone agreed to cannot be read or edited by anyone holding
 * the file.
 *
 * A singleton row, like the profile: one device, one person, one consent. There
 * is no query that could return two, and a second acceptance replaces the first
 * rather than appending - the record is the current state of consent, not an
 * audit log of every time the screen was shown. An audit log would be a longer
 * retention of the same personal data for no user benefit.
 */

package com.lumos.app.onboarding;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.util.Optional;

/** Reads and writes the stored consent record. */
public final class ConsentRepository {

    /** The single-row identifier for the consent record. */
    public static final String CONSENT_ID = "self";

    private final LocalStore store;

    /**
     * Binds a repository to a store.
     *
     * @param store the opened local store
     */
    public ConsentRepository(final LocalStore store) {
        this.store = store;
    }

    /**
     * Records consent, replacing any previous record.
     *
     * @param record what was affirmed and accepted
     * @throws StoreException on low storage or engine failure
     */
    public void save(final ConsentRecord record) throws StoreException {
        store.put(RecordKind.CONSENT_RECORD, CONSENT_ID, ConsentCodec.encode(record), null);
    }

    /**
     * Loads the consent record.
     *
     * @return the record, or empty when consent has never been given
     * @throws StoreException if the record exists but cannot be opened
     */
    public Optional<ConsentRecord> load() throws StoreException {
        return store.get(RecordKind.CONSENT_RECORD, CONSENT_ID).map(ConsentCodec::decode);
    }

    /**
     * Whether the user must be shown the documents again (SRS-ONB-002).
     *
     * <p>True when no consent exists, and true when any document's shipped
     * version differs from the accepted one. Absence and staleness are the same
     * answer to the only question the caller is asking.
     *
     * @param current the versions the shipped documents declare now
     * @return true when consent must be taken
     * @throws StoreException on engine failure
     */
    public boolean needsConsent(final LegalDocuments current) throws StoreException {
        final Optional<ConsentRecord> stored = load();
        return stored.isEmpty() || !stored.get().outdatedAgainst(current.versions()).isEmpty();
    }
}

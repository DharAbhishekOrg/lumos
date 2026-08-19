/*
 * Lumos - android-app/app / Storing and replacing the device identity.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-IDN-003, SRS-ONB-006, SRS-DAT-002
 * ADRs: ADR-003, ADR-008
 * Threading: serial-executor
 * Notes: A Repository (TRS Sec. 24.2) over the IDENTITY_KEY_RECORD table. One
 * device, one identity, one row.
 *
 * replace() is what an identity reset and a recovery restore both go through,
 * and it is deliberately the same method: SRS-ONB-007 says restore creates a
 * NEW cryptographic identity, and SRS-IDN-003 says a reset does, and if those
 * were two code paths one of them would eventually keep the old keys. Both call
 * this, and both are proven fresh by comparing the Ed25519 public key before
 * and after.
 */

package com.lumos.app.identity;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.util.Optional;

/** Reads and replaces the device's identity record. */
public final class IdentityRepository {

    /** The single-row identifier for the device identity. */
    public static final String IDENTITY_ID = "self";

    private final LocalStore store;

    /**
     * Binds a repository to a store.
     *
     * @param store the opened local store
     */
    public IdentityRepository(final LocalStore store) {
        this.store = store;
    }

    /**
     * Whether an identity exists on this device.
     *
     * @return true when a record is stored
     * @throws StoreException on engine failure
     */
    public boolean exists() throws StoreException {
        return store.get(RecordKind.IDENTITY_KEY_RECORD, IDENTITY_ID).isPresent();
    }

    /**
     * Loads the identity.
     *
     * @return the record, or empty when the device has no identity yet
     * @throws StoreException if the record exists but cannot be opened
     */
    public Optional<IdentityKeyRecord> load() throws StoreException {
        return store.get(RecordKind.IDENTITY_KEY_RECORD, IDENTITY_ID).map(IdentityCodec::decode);
    }

    /**
     * Writes the identity, replacing whatever was there.
     *
     * <p>Replacing rather than adding is the point. The previous identity's
     * private keys were only ever readable through the KEK-wrapped envelope in
     * the row this overwrites, so once this returns they are gone from the
     * store; and the identity reset that calls it destroys the KEK as well, so
     * they are gone from the device.
     *
     * @param record the identity to store
     * @throws StoreException on low storage or engine failure
     */
    public void replace(final IdentityKeyRecord record) throws StoreException {
        store.put(RecordKind.IDENTITY_KEY_RECORD, IDENTITY_ID, IdentityCodec.encode(record), null);
    }
}

/*
 * Lumos - android-app/app / Profile CRUD over the sealed store.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-003, SRS-PRO-009, SRS-PRO-011, SRS-DAT-002
 * ADRs: ADR-003
 * Threading: serial-executor
 * Notes: The Repository of TRS Sec. 24.2, and the only place profile content
 * meets storage. It encodes to a payload, hands that to LocalStore, and never
 * sees a database - so the encryption, the AAD binding and the low-storage
 * guard are all inherited rather than reimplemented.
 *
 * Profile and policy are SEPARATE records on purpose. They change at different
 * times and for different reasons, and keeping them apart means a policy edit
 * does not rewrite the profile blob - which matters because a rewritten blob is
 * a new ciphertext, and an observer watching file mtimes learns something from
 * the difference between "changed their bio" and "changed who can see it".
 *
 * Deletion is deletion (SRS-PRO-011): the row goes, no tombstone. A tombstone
 * would retain the data the user asked to erase, which is the whole point of
 * the action.
 */

package com.lumos.app.profile;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.util.Optional;

/** Reads and writes the profile and its visibility policy. */
public final class ProfileRepository {

    /**
     * The single-row identifier for the local profile.
     *
     * <p>The profile is a singleton: one device, one owner, one profile. A
     * fixed identifier makes that structural rather than conventional - there
     * is no query that could return two.
     */
    public static final String PROFILE_ID = "self";

    /** The single-row identifier for the visibility policy. */
    public static final String POLICY_ID = "self";

    private final LocalStore store;

    /**
     * Binds a repository to a store.
     *
     * @param store the opened local store
     */
    public ProfileRepository(final LocalStore store) {
        this.store = store;
    }

    /**
     * Saves the profile, replacing any previous version.
     *
     * @param profile the profile
     * @throws StoreException on low storage or engine failure
     */
    public void save(final LocalProfile profile) throws StoreException {
        store.put(RecordKind.LOCAL_PROFILE, PROFILE_ID, ProfileCodec.encodeProfile(profile), null);
    }

    /**
     * Loads the profile.
     *
     * @return the profile, or empty when none has been created
     * @throws StoreException if the record exists but cannot be opened
     */
    public Optional<LocalProfile> load() throws StoreException {
        return store.get(RecordKind.LOCAL_PROFILE, PROFILE_ID).map(ProfileCodec::decodeProfile);
    }

    /**
     * Saves the visibility policy.
     *
     * @param policy the policy
     * @throws StoreException on low storage or engine failure
     */
    public void savePolicy(final ProfileFieldPolicy policy) throws StoreException {
        store.put(RecordKind.PROFILE_FIELD_POLICY, POLICY_ID,
                ProfileCodec.encodePolicy(policy), null);
    }

    /**
     * Loads the visibility policy.
     *
     * <p>An absent policy reads as {@link ProfileFieldPolicy#privateByDefault()},
     * never as "no restrictions". A missing record must fail towards privacy:
     * the alternative is that a storage problem silently publishes every field.
     *
     * @return the stored policy, or the private default
     * @throws StoreException if the record exists but cannot be opened
     */
    public ProfileFieldPolicy loadPolicy() throws StoreException {
        return store.get(RecordKind.PROFILE_FIELD_POLICY, POLICY_ID)
                .map(ProfileCodec::decodePolicy)
                .orElseGet(ProfileFieldPolicy::privateByDefault);
    }

    /**
     * Derives what an audience may see, from the stored profile and policy.
     *
     * <p>This is the SRS-PRO-008 privacy preview: the user asks "what does a
     * nearby person see?" and gets the answer computed the same way the sharing
     * path computes it, because it is the same function.
     *
     * @param audience who is looking
     * @return the preview, or empty when no profile exists
     * @throws StoreException on engine failure
     */
    public Optional<ProfilePreview> previewFor(final ProfileFieldPolicy.Audience audience)
            throws StoreException {
        final Optional<LocalProfile> profile = load();
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ProfilePreview.derive(profile.get(), loadPolicy(), audience));
    }

    /**
     * Permanently deletes the profile and its policy (SRS-PRO-011).
     *
     * <p>No tombstone: the rows go. Retaining a marker would retain the data
     * the user asked to erase.
     *
     * @throws StoreException on engine failure
     */
    public void deletePermanently() throws StoreException {
        store.delete(RecordKind.LOCAL_PROFILE, PROFILE_ID);
        store.delete(RecordKind.PROFILE_FIELD_POLICY, POLICY_ID);
    }
}

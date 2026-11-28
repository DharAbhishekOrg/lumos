/*
 * Lumos - android-app/app / Where the Profile tab reads and writes the person's profile.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-003, SRS-PRO-004, SRS-PRO-008
 * ADRs: ADR-003
 * Threading: serial-executor (the store's); never the main thread
 * Notes: The seam between ProfilePanel and the store, so the instrumented
 * test drives the screen with a script and the device uses the sealed
 * PROFILE and PROFILE_POLICY rows through ProfileRepository (encrypted at
 * rest, SRS-PRO-004). A source that cannot read says so with an empty
 * answer, and the screen says so rather than showing a blank form as if it
 * were the person's real profile.
 */

package com.lumos.app.profile;

import com.lumos.app.store.StoreException;
import java.util.Optional;

/** The person's own profile and its visibility policy. */
public interface ProfileSource {

    /**
     * A profile with its policy, as edited together.
     *
     * @param profile the profile (empty when none was saved yet)
     * @param policy who may see each field
     */
    record Editing(LocalProfile profile, ProfileFieldPolicy policy) { }

    /**
     * @return the stored profile and policy (an empty profile and the private
     *     default when none exist), or empty when the store cannot be read
     */
    Optional<Editing> load();

    /**
     * Stores the profile and its policy together.
     *
     * @param editing what to store
     * @return true once stored
     */
    boolean save(Editing editing);

    /** The device source: the sealed rows behind ProfileRepository. */
    final class Stored implements ProfileSource {
        private final ProfileRepository repository;

        /**
         * @param repository the repository over the install's store
         */
        public Stored(final ProfileRepository repository) {
            this.repository = repository;
        }

        @Override
        public Optional<Editing> load() {
            try {
                return Optional.of(new Editing(repository.load().orElse(LocalProfile.empty()),
                        repository.loadPolicy()));
            } catch (final StoreException unreadable) {
                return Optional.empty();
            }
        }

        @Override
        public boolean save(final Editing editing) {
            try {
                repository.savePolicy(editing.policy());
                repository.save(editing.profile());
                return true;
            } catch (final StoreException notStored) {
                return false;
            }
        }
    }
}

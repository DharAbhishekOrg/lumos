/*
 * Lumos - android-app/app / Profile photos: add, choose the main one, reorder, remove.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-004, SRS-PRO-005, SRS-PRO-006, SRS-PRO-009
 * ADRs: ADR-003
 * Threading: the store's executor (every method reads or writes storage)
 * Notes: A picked picture walks one path only, in this order: the platform
 * re-encoder draws it again as a fresh JPEG (no camera metadata survives a
 * re-draw), MediaSanitizer checks the type, size and dimensions and strips
 * every metadata container again (SRS-PRO-005/006, defence in depth), the
 * encrypted object store keeps the bytes (SRS-PRO-004), and only then does
 * the profile gain a ProfileMedia reference. The profile is saved before an
 * object is deleted and after an object is written, so a failed save never
 * leaves the profile pointing at nothing: a new object whose profile save
 * failed is deleted again, and a removed photo's object is deleted only once
 * the profile without it is stored. Anything left over by a crash between
 * those steps is an encrypted object nobody references; sweep() deletes it.
 * Everything here works offline (SRS-PRO-009). The first photo is the main
 * one, so choosing a main photo and reordering are the same list operation.
 */

package com.lumos.app.profile;

import com.lumos.app.media.MediaObjectStore;
import com.lumos.app.media.MediaSanitizer;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The profile-photo operations, over a re-encoder, an object vault and the profile store. */
public final class ProfilePhotos {

    /**
     * The most photos a profile holds.
     *
     * <p>A named local constant with rationale (TRS-CODE-005): the register
     * bounds each image's bytes but not how many a profile keeps. Six keeps
     * the encrypted store small (at most six times FULL_IMAGE_MAX_MB) and is
     * the common ceiling people expect from dating apps.
     */
    public static final int MAX_PHOTOS = 6;

    /** Draws a picked picture again as a fresh, bounded JPEG. */
    public interface Reencoder {
        /**
         * @param picked the bytes as the picker gave them
         * @return a freshly encoded JPEG within MediaSanitizer.MAX_IMAGE_BYTES
         * @throws MediaSanitizer.RejectedException when the picture cannot be
         *     decoded safely
         */
        byte[] reencode(byte[] picked) throws MediaSanitizer.RejectedException;
    }

    /** Where photo bytes are kept, encrypted. */
    public interface Vault {
        /**
         * @param sanitised bytes the sanitiser produced
         * @return the new object's identifier
         * @throws IOException when nothing was stored
         */
        String put(byte[] sanitised) throws IOException;

        /**
         * @param objectId a stored object
         * @return its plaintext bytes
         * @throws IOException when it is missing or does not open
         */
        byte[] get(String objectId) throws IOException;

        /**
         * @param objectId an object to delete; an absent one is not an error
         * @throws IOException when the object could not be deleted
         */
        void delete(String objectId) throws IOException;

        /**
         * @return every stored object's identifier
         * @throws IOException when the store cannot be listed
         */
        List<String> list() throws IOException;
    }

    /**
     * The device vault: the encrypted MediaObjectStore. A cipher failure is
     * reported as an IOException, because to this class both mean "not done".
     *
     * @param store the opened store
     * @return the vault
     */
    public static Vault vaultOf(final MediaObjectStore store) {
        return new Vault() {
            @Override
            public String put(final byte[] sanitised) throws IOException {
                try {
                    return store.put(sanitised);
                } catch (final GeneralSecurityException cipher) {
                    throw new IOException("media object not sealed", cipher);
                }
            }

            @Override
            public byte[] get(final String objectId) throws IOException {
                try {
                    return store.get(objectId);
                } catch (final GeneralSecurityException unopened) {
                    throw new IOException("media object does not open", unopened);
                }
            }

            @Override
            public void delete(final String objectId) throws IOException {
                try {
                    store.delete(objectId);
                } catch (final GeneralSecurityException notOurs) {
                    throw new IOException("not a media object identifier", notOurs);
                }
            }

            @Override
            public List<String> list() throws IOException {
                return store.list();
            }
        };
    }

    /** What an operation did. */
    public enum Outcome {
        /** Done and stored. */
        DONE,
        /** Already MAX_PHOTOS photos. */
        FULL,
        /** The picture failed a safety check and was not kept. */
        REJECTED,
        /** The store refused; nothing changed. */
        NOT_SAVED,
        /** No such photo on the profile. */
        UNKNOWN
    }

    /**
     * An operation's result.
     *
     * @param outcome what happened
     * @param editing the stored profile afterwards, when it could be read
     */
    public record Change(Outcome outcome, Optional<ProfileSource.Editing> editing) { }

    private final Reencoder reencoder;
    private final Vault vault;
    private final ProfileSource source;

    /**
     * @param reencoder the platform re-encoder
     * @param vault the encrypted object store
     * @param source the profile store
     */
    public ProfilePhotos(final Reencoder reencoder, final Vault vault, final ProfileSource source) {
        this.reencoder = reencoder;
        this.vault = vault;
        this.source = source;
    }

    /**
     * Adds a picked picture as the last photo (the main one if it is the first).
     *
     * @param editing the profile on screen
     * @param picked the picker's bytes
     * @return the outcome and the stored profile
     */
    public Change add(final ProfileSource.Editing editing, final byte[] picked) {
        if (editing.profile().media().size() >= MAX_PHOTOS) {
            return new Change(Outcome.FULL, source.load());
        }
        final MediaSanitizer.SanitizedImage image;
        try {
            image = MediaSanitizer.sanitize(reencoder.reencode(picked));
        } catch (final MediaSanitizer.RejectedException unsafe) {
            return new Change(Outcome.REJECTED, source.load());
        }
        final String objectId;
        try {
            objectId = vault.put(image.bytes());
        } catch (final IOException notKept) {
            return new Change(Outcome.NOT_SAVED, source.load());
        }
        final List<ProfileMedia> next = new ArrayList<>(editing.profile().media());
        next.add(new ProfileMedia(objectId, image.type().mimeType(), image.width(), image.height(),
                image.bytes().length));
        if (source.save(new ProfileSource.Editing(editing.profile().withMedia(next), editing.policy()))) {
            return new Change(Outcome.DONE, source.load());
        }
        deleteQuietly(objectId);
        return new Change(Outcome.NOT_SAVED, source.load());
    }

    /**
     * Makes one photo the main one (moves it first).
     *
     * @param editing the profile on screen
     * @param objectId the photo
     * @return the outcome and the stored profile
     */
    public Change makePrimary(final ProfileSource.Editing editing, final String objectId) {
        return move(editing, objectId, 0);
    }

    /**
     * Moves one photo to a new place in the order.
     *
     * @param editing the profile on screen
     * @param objectId the photo
     * @param toIndex its new place, clamped to the list
     * @return the outcome and the stored profile
     */
    public Change move(final ProfileSource.Editing editing, final String objectId, final int toIndex) {
        final List<ProfileMedia> next = new ArrayList<>(editing.profile().media());
        final int from = indexOf(next, objectId);
        if (from < 0) {
            return new Change(Outcome.UNKNOWN, source.load());
        }
        final ProfileMedia moved = next.remove(from);
        next.add(Math.max(0, Math.min(toIndex, next.size())), moved);
        final boolean stored = source.save(
                new ProfileSource.Editing(editing.profile().withMedia(next), editing.policy()));
        return new Change(stored ? Outcome.DONE : Outcome.NOT_SAVED, source.load());
    }

    /**
     * Removes one photo: the profile without it is stored first, then its bytes are deleted.
     *
     * @param editing the profile on screen
     * @param objectId the photo
     * @return the outcome and the stored profile
     */
    public Change remove(final ProfileSource.Editing editing, final String objectId) {
        final List<ProfileMedia> next = new ArrayList<>(editing.profile().media());
        final int at = indexOf(next, objectId);
        if (at < 0) {
            return new Change(Outcome.UNKNOWN, source.load());
        }
        next.remove(at);
        if (!source.save(new ProfileSource.Editing(editing.profile().withMedia(next), editing.policy()))) {
            return new Change(Outcome.NOT_SAVED, source.load());
        }
        deleteQuietly(objectId);
        return new Change(Outcome.DONE, source.load());
    }

    /**
     * One photo's bytes, for drawing it on this phone only.
     *
     * @param objectId the photo
     * @return the sanitised bytes, or empty when the object does not open
     */
    public Optional<byte[]> bytes(final String objectId) {
        try {
            return Optional.of(vault.get(objectId));
        } catch (final IOException unopened) {
            return Optional.empty();
        }
    }

    /**
     * Deletes every stored object the stored profile does not reference.
     *
     * <p>Only when the profile can be read: an unreadable profile could
     * reference anything, so nothing is deleted then.
     *
     * @return how many objects were deleted
     */
    public int sweep() {
        final Optional<ProfileSource.Editing> stored = source.load();
        if (stored.isEmpty()) {
            return 0;
        }
        final Set<String> kept = new HashSet<>();
        for (final ProfileMedia media : stored.get().profile().media()) {
            kept.add(media.objectId());
        }
        int deleted = 0;
        try {
            for (final String objectId : vault.list()) {
                if (!kept.contains(objectId) && deleteQuietly(objectId)) {
                    deleted++;
                }
            }
        } catch (final IOException unlisted) {
            return deleted;
        }
        return deleted;
    }

    private boolean deleteQuietly(final String objectId) {
        try {
            vault.delete(objectId);
            return true;
        } catch (final IOException leftOver) {
            // An encrypted object nobody references; the next sweep() takes it.
            return false;
        }
    }

    private static int indexOf(final List<ProfileMedia> media, final String objectId) {
        for (int i = 0; i < media.size(); i++) {
            if (media.get(i).objectId().equals(objectId)) {
                return i;
            }
        }
        return -1;
    }
}

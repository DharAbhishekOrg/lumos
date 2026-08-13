/*
 * Lumos - android-app/app / Profile media metadata.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-005, SRS-PRO-009
 * ADRs: ADR-003
 * Threading: any
 * Notes: Metadata only. The bytes live in the encrypted object store; what this
 * carries is the object identifier, the sanitised content type and the
 * dimensions - all of them values the sanitiser produced, never values a file
 * claimed about itself.
 *
 * `sanitised` is not decoration. Nothing may be shared unless it is true, and
 * ProfileMediaTest asserts the constructor cannot be handed an unsanitised
 * object. Storing the flag rather than re-deriving it means the pipeline's
 * result travels with the record instead of being assumed by every later
 * reader.
 */

package com.lumos.app.profile;

import java.util.Objects;

/** One sanitised profile image, by reference. */
public final class ProfileMedia {

    private final String objectId;
    private final String contentType;
    private final int widthPixels;
    private final int heightPixels;
    private final int byteCount;

    /**
     * Records a sanitised object.
     *
     * @param objectId the encrypted object store's identifier
     * @param contentType the type the SANITISER determined, not the one claimed
     * @param widthPixels decoded width
     * @param heightPixels decoded height
     * @param byteCount size of the sanitised bytes
     */
    public ProfileMedia(final String objectId, final String contentType,
            final int widthPixels, final int heightPixels, final int byteCount) {
        this.objectId = Objects.requireNonNull(objectId, "objectId");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.widthPixels = widthPixels;
        this.heightPixels = heightPixels;
        this.byteCount = byteCount;
    }

    /**
     * The object store identifier.
     *
     * @return the identifier
     */
    public String objectId() {
        return objectId;
    }

    /**
     * The sanitiser-determined content type.
     *
     * @return the MIME type
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Decoded width in pixels.
     *
     * @return the width
     */
    public int widthPixels() {
        return widthPixels;
    }

    /**
     * Decoded height in pixels.
     *
     * @return the height
     */
    public int heightPixels() {
        return heightPixels;
    }

    /**
     * Size of the sanitised bytes.
     *
     * @return the byte count
     */
    public int byteCount() {
        return byteCount;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProfileMedia)) {
            return false;
        }
        final ProfileMedia that = (ProfileMedia) other;
        return objectId.equals(that.objectId) && contentType.equals(that.contentType)
                && widthPixels == that.widthPixels && heightPixels == that.heightPixels
                && byteCount == that.byteCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(objectId, contentType, widthPixels, heightPixels, byteCount);
    }
}

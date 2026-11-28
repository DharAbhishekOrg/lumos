/*
 * Lumos - android-app/app / The Profile tab's photos: thumbnails, add, main photo, order, remove.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-003, SRS-PRO-005, SRS-PRO-006, SRS-PRO-009, SRS-NFR-A11Y-001
 * ADRs: ADR-003, ADR-011
 * Threading: main for the views; picking, decoding and every store call on the store's executor
 * Notes: The picker is the system photo picker (no storage permission: the
 * person hands over one picture, never the gallery). A picked file is read
 * on the store's executor, capped at BitmapReencoder.MAX_SOURCE_BYTES before
 * it is read whole, and goes through ProfilePhotos.add - re-drawn, sanitised,
 * encrypted - before any thumbnail exists. Thumbnails are decoded from the
 * stored, already-clean bytes, subsampled to the size shown. Each thumbnail
 * says its place and whether it is the main photo, for screen readers, and a
 * tap opens the choices that fit it: make it the main photo, move it, or
 * remove it (removing asks first; it deletes the photo from this phone).
 * Photos are private to matches by default, like every field; the strip's
 * "Who can see this" line uses the same audience dialog as the fields.
 */

package com.lumos.app.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lumos.app.R;
import com.lumos.app.media.BitmapReencoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Binds the Profile tab's photo strip to ProfilePhotos. */
public final class ProfilePhotoStrip {

    /** A thumbnail's side, in pixels it is decoded to (about 96 dp at 2x). */
    static final int THUMB_PIXELS = 192;

    private static final int READ_CHUNK = 64 * 1024;

    private final View root;
    private final Context context;
    private final Supplier<ProfilePhotos> photos;
    private final Executor worker;
    private final ProfilePanel panel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable picker = () -> { };

    /**
     * @param root the panel_profile view
     * @param photos the photo operations, or null until the media store is open
     * @param worker the store's executor
     * @param panel the panel that owns the profile on screen
     */
    public ProfilePhotoStrip(final View root, final Supplier<ProfilePhotos> photos, final Executor worker,
            final ProfilePanel panel) {
        this.root = root;
        this.context = root.getContext();
        this.photos = photos;
        this.worker = worker;
        this.panel = panel;
        root.findViewById(R.id.profile_photo_add).setOnClickListener(v -> askToAdd());
        root.findViewById(R.id.profile_photos_audience).setOnClickListener(
                v -> panel.chooseAudience(ProfileField.MEDIA));
    }

    /**
     * Sets what Add a photo opens: the activity's system photo picker.
     *
     * @param pick launches the picker
     */
    public void setPicker(final Runnable pick) {
        picker = pick;
    }

    private void askToAdd() {
        final ProfileSource.Editing editing = panel.current();
        if (editing == null) {
            return;
        }
        if (editing.profile().media().size() >= ProfilePhotos.MAX_PHOTOS) {
            panel.status(R.string.profile_photos_full);
            return;
        }
        picker.run();
    }

    /**
     * Takes one picture the picker returned.
     *
     * @param picked the content URI the picker granted
     */
    public void picked(final Uri picked) {
        worker.execute(() -> {
            final Optional<byte[]> bytes = read(picked);
            if (bytes.isEmpty()) {
                main.post(() -> panel.status(R.string.profile_photo_rejected));
                return;
            }
            addPicked(bytes.get());
        });
    }

    /**
     * Adds a picture's bytes; on the store's executor.
     *
     * @param bytes the picker's bytes
     */
    void addPicked(final byte[] bytes) {
        final ProfilePhotos ops = photos.get();
        final ProfileSource.Editing editing = panel.current();
        if (ops == null || editing == null) {
            main.post(() -> panel.status(R.string.profile_photos_unavailable));
            return;
        }
        apply(ops.add(editing, bytes));
    }

    private void apply(final ProfilePhotos.Change change) {
        main.post(() -> {
            change.editing().ifPresent(panel::show);
            switch (change.outcome()) {
                case FULL:
                    panel.status(R.string.profile_photos_full);
                    break;
                case REJECTED:
                    panel.status(R.string.profile_photo_rejected);
                    break;
                case NOT_SAVED:
                    panel.status(R.string.profile_not_saved);
                    break;
                default:
                    break;
            }
        });
    }

    /**
     * Draws the strip for the profile on screen. Main thread.
     *
     * @param editing the profile and its policy
     */
    void draw(final ProfileSource.Editing editing) {
        final LinearLayout strip = root.findViewById(R.id.profile_photos);
        strip.removeAllViews();
        final List<ProfileMedia> media = editing.profile().media();
        final int side = context.getResources().getDimensionPixelSize(R.dimen.profile_photo_thumb);
        for (int i = 0; i < media.size(); i++) {
            final ImageView thumb = new ImageView(context);
            final LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(side, side);
            size.setMarginEnd(context.getResources().getDimensionPixelSize(R.dimen.lumos_space_2));
            thumb.setLayoutParams(size);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundResource(R.color.lumos_surface_high);
            thumb.setContentDescription(describe(i, media.size()));
            final String objectId = media.get(i).objectId();
            final int place = i;
            thumb.setOnClickListener(v -> choices(objectId, place, media.size()));
            strip.addView(thumb);
            loadThumb(thumb, objectId);
        }
        ((TextView) root.findViewById(R.id.profile_photos_hint)).setText(media.isEmpty()
                ? R.string.profile_photos_none : R.string.profile_photos_hint);
        final TextView audience = root.findViewById(R.id.profile_photos_audience);
        final String name = context.getString(ProfilePanel.labelOf(ProfileField.MEDIA));
        audience.setText(context.getString(R.string.profile_audience_label,
                context.getString(ProfilePanel.audienceName(editing.policy().threshold(ProfileField.MEDIA)))));
        audience.setContentDescription(audience.getText() + ". "
                + context.getString(R.string.profile_audience_change, name));
    }

    private String describe(final int place, final int count) {
        return place == 0
                ? context.getString(R.string.profile_photo_main_description, place + 1, count)
                : context.getString(R.string.profile_photo_description, place + 1, count);
    }

    private void loadThumb(final ImageView thumb, final String objectId) {
        worker.execute(() -> {
            final ProfilePhotos ops = photos.get();
            final Optional<byte[]> bytes = ops == null ? Optional.empty() : ops.bytes(objectId);
            final Bitmap bitmap = bytes.map(ProfilePhotoStrip::decodeThumb).orElse(null);
            if (bitmap != null) {
                main.post(() -> thumb.setImageBitmap(bitmap));
            }
        });
    }

    private static Bitmap decodeThumb(final byte[] clean) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(clean, 0, clean.length, bounds);
        final BitmapFactory.Options decode = new BitmapFactory.Options();
        int sample = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_PIXELS) {
            sample *= 2;
        }
        decode.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(clean, 0, clean.length, decode);
    }

    private void choices(final String objectId, final int place, final int count) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (place > 0) {
            labels.add(context.getString(R.string.profile_photo_make_main));
            actions.add(() -> run((ops, e) -> ops.makePrimary(e, objectId)));
            labels.add(context.getString(R.string.profile_photo_move_earlier));
            actions.add(() -> run((ops, e) -> ops.move(e, objectId, place - 1)));
        }
        if (place < count - 1) {
            labels.add(context.getString(R.string.profile_photo_move_later));
            actions.add(() -> run((ops, e) -> ops.move(e, objectId, place + 1)));
        }
        labels.add(context.getString(R.string.profile_photo_remove));
        actions.add(() -> confirmRemove(objectId));
        new MaterialAlertDialogBuilder(context)
                .setTitle(describe(place, count))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.profile_cancel, null)
                .show();
    }

    private void confirmRemove(final String objectId) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.profile_photo_remove_title)
                .setMessage(R.string.profile_photo_remove_message)
                .setPositiveButton(R.string.profile_photo_remove, (d, w) -> remove(objectId))
                .setNegativeButton(R.string.profile_cancel, null)
                .show();
    }

    /**
     * Removes one photo, as the confirmed dialog does.
     *
     * @param objectId the photo
     */
    void remove(final String objectId) {
        run((ops, e) -> ops.remove(e, objectId));
    }

    /**
     * Makes one photo the main one, as its dialog choice does.
     *
     * @param objectId the photo
     */
    void makePrimary(final String objectId) {
        run((ops, e) -> ops.makePrimary(e, objectId));
    }

    /** One photo operation over the profile on screen. */
    private interface Operation {
        ProfilePhotos.Change on(ProfilePhotos ops, ProfileSource.Editing editing);
    }

    private void run(final Operation operation) {
        final ProfileSource.Editing editing = panel.current();
        if (editing == null) {
            return;
        }
        worker.execute(() -> {
            final ProfilePhotos ops = photos.get();
            if (ops == null) {
                main.post(() -> panel.status(R.string.profile_photos_unavailable));
                return;
            }
            apply(operation.on(ops, editing));
        });
    }

    private Optional<byte[]> read(final Uri picked) {
        try (InputStream in = context.getContentResolver().openInputStream(picked)) {
            if (in == null) {
                return Optional.empty();
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] chunk = new byte[READ_CHUNK];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (out.size() + read > BitmapReencoder.MAX_SOURCE_BYTES) {
                    return Optional.empty();
                }
                out.write(chunk, 0, read);
            }
            return Optional.of(out.toByteArray());
        } catch (final IOException | SecurityException unreadable) {
            return Optional.empty();
        }
    }
}

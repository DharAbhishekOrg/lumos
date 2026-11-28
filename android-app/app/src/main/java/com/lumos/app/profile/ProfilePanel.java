/*
 * Lumos - android-app/app / The Profile tab: edit each field, choose who sees it, see what others see.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-PRO-001, SRS-PRO-002, SRS-PRO-003, SRS-PRO-004, SRS-PRO-008, SRS-PRO-010,
 *             SRS-PRO-012, SRS-NFR-A11Y-001
 * ADRs: ADR-003
 * Threading: main for the views; every read and write on the store's executor
 * Notes: Photos first (ProfilePhotoStrip, when the media store is open), then
 * one line per field, in the order SRS-PRO-001 lists them. Tapping a field edits it: free words for everything but
 * age, which is a band; every optional field also offers "Prefer not to say",
 * which counts as answered and is never shown as a value to anyone else
 * (SRS-PRO-002). Each field's "Who can see this" line opens the three
 * audiences a person can choose today; the default for every field is "Only
 * your matches" (the policy's most private default, SRS-PRO-003). "See what
 * others see" derives the preview exactly as the sharing path will - the
 * same ProfilePreview.derive - for three audiences (SRS-PRO-008). While the
 * name or age is missing, a note says the profile cannot be seen yet
 * (SRS-PRO-010). A save that fails leaves the screen on what is stored.
 */

package com.lumos.app.profile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lumos.app.R;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/** Binds the Profile tab's views to a ProfileSource. */
public final class ProfilePanel {

    /** The fields this tab edits as words, in SRS-PRO-001 order (photos have their own strip). */
    static final List<ProfileField> EDITED = List.of(ProfileField.ALIAS, ProfileField.AGE_BAND,
            ProfileField.PRONOUNS, ProfileField.GENDER_IDENTITY, ProfileField.ORIENTATION,
            ProfileField.RELATIONSHIP_INTENT, ProfileField.INTERESTS, ProfileField.BIO);

    /** The audiences a person can choose today, least to most private. */
    static final List<ProfileFieldPolicy.Audience> CHOOSABLE = List.of(ProfileFieldPolicy.Audience.NEARBY,
            ProfileFieldPolicy.Audience.REQUESTER, ProfileFieldPolicy.Audience.MATCH);

    /** Longest answer for a one-line field. */
    static final int SHORT_MAX_CHARS = 60;
    /** Longest answer for interests and the bio. */
    static final int LONG_MAX_CHARS = 500;

    private final View root;
    private final Context context;
    private final ProfileSource source;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ProfileSource.Editing current;
    private ProfilePhotoStrip photos;

    /**
     * @param root the panel_profile view
     * @param source where the profile lives
     * @param worker the store's executor
     */
    public ProfilePanel(final View root, final ProfileSource source, final Executor worker) {
        this.root = root;
        this.context = root.getContext();
        this.source = source;
        this.worker = worker;
        root.findViewById(R.id.profile_preview_nearby).setOnClickListener(
                v -> preview(ProfileFieldPolicy.Audience.NEARBY, R.string.profile_preview_as_nearby));
        root.findViewById(R.id.profile_preview_requester).setOnClickListener(
                v -> preview(ProfileFieldPolicy.Audience.REQUESTER, R.string.profile_preview_as_requester));
        root.findViewById(R.id.profile_preview_match).setOnClickListener(
                v -> preview(ProfileFieldPolicy.Audience.MATCH, R.string.profile_preview_as_match));
    }

    /** Reads the stored profile and draws it. Main thread. */
    public void load() {
        worker.execute(() -> {
            final Optional<ProfileSource.Editing> read = source.load();
            main.post(() -> {
                if (read.isPresent()) {
                    draw(read.get());
                } else {
                    status(R.string.profile_unreadable);
                }
            });
        });
    }

    /** @return the profile on screen, or null before the first read */
    ProfileSource.Editing current() {
        return current;
    }

    /**
     * Shows the photo strip, drawn with every profile from now on. Main thread.
     *
     * @param strip the strip over the media store
     */
    public void attachPhotos(final ProfilePhotoStrip strip) {
        photos = strip;
        root.findViewById(R.id.profile_photos_section).setVisibility(View.VISIBLE);
        if (current != null) {
            strip.draw(current);
        }
    }

    /**
     * Puts a stored profile on screen; what the photo strip does after a change.
     *
     * @param editing the stored profile and policy
     */
    void show(final ProfileSource.Editing editing) {
        draw(editing);
    }

    private void draw(final ProfileSource.Editing editing) {
        current = editing;
        if (photos != null) {
            photos.draw(editing);
        }
        final LinearLayout fields = root.findViewById(R.id.profile_fields);
        fields.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(context);
        for (final ProfileField field : EDITED) {
            final View row = inflater.inflate(R.layout.item_profile_field, fields, false);
            final String label = context.getString(labelOf(field));
            ((TextView) row.findViewById(R.id.profile_field_label)).setText(label);
            ((TextView) row.findViewById(R.id.profile_field_value)).setText(shown(editing.profile().value(field)));
            final TextView audience = row.findViewById(R.id.profile_field_audience);
            audience.setText(context.getString(R.string.profile_audience_label,
                    context.getString(audienceName(editing.policy().threshold(field)))));
            audience.setContentDescription(audience.getText() + ". "
                    + context.getString(R.string.profile_audience_change, label));
            row.findViewById(R.id.profile_field_edit).setOnClickListener(v -> edit(field));
            audience.setOnClickListener(v -> chooseAudience(field));
            fields.addView(row);
        }
        if (editing.profile().isShowable()) {
            root.findViewById(R.id.profile_status).setVisibility(View.GONE);
        } else {
            status(R.string.profile_incomplete);
        }
    }

    /**
     * Shows one sentence above the fields.
     *
     * @param text the sentence
     */
    void status(final int text) {
        final TextView status = root.findViewById(R.id.profile_status);
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private String shown(final FieldValue value) {
        switch (value.kind()) {
            case STATED:
                return value.text();
            case PREFER_NOT_TO_SAY:
                return context.getString(R.string.profile_value_declined);
            default:
                return context.getString(R.string.profile_value_unset);
        }
    }

    private void edit(final ProfileField field) {
        if (current == null) {
            return;
        }
        if (field == ProfileField.AGE_BAND) {
            editAge();
            return;
        }
        final FieldValue now = current.profile().value(field);
        final EditText input = new EditText(context);
        input.setHint(hintOf(field));
        input.setMinHeight(context.getResources().getDimensionPixelSize(R.dimen.lumos_space_12));
        final boolean longText = field == ProfileField.BIO || field == ProfileField.INTERESTS;
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (longText ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(longText ? LONG_MAX_CHARS : SHORT_MAX_CHARS)});
        if (now.kind() == FieldValue.Kind.STATED) {
            input.setText(now.text());
        }
        final MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(labelOf(field))
                .setView(input)
                .setPositiveButton(R.string.profile_save,
                        (d, which) -> setValue(field, FieldValue.stated(input.getText().toString())))
                .setNegativeButton(R.string.profile_cancel, null);
        if (!field.isRequiredForCompleteness()) {
            dialog.setNeutralButton(R.string.profile_decline,
                    (d, which) -> setValue(field, FieldValue.preferNotToSay()));
        }
        dialog.show();
    }

    private void editAge() {
        final String[] bands = context.getResources().getStringArray(R.array.profile_age_bands);
        final FieldValue now = current.profile().value(ProfileField.AGE_BAND);
        int checked = -1;
        for (int i = 0; i < bands.length; i++) {
            if (now.kind() == FieldValue.Kind.STATED && bands[i].equals(now.text())) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.profile_field_age_band)
                .setSingleChoiceItems(bands, checked, (d, which) -> {
                    d.dismiss();
                    setValue(ProfileField.AGE_BAND, FieldValue.stated(bands[which]));
                })
                .setNegativeButton(R.string.profile_cancel, null)
                .show();
    }

    /**
     * Opens the audience dialog for one field.
     *
     * @param field the field
     */
    void chooseAudience(final ProfileField field) {
        if (current == null) {
            return;
        }
        final String[] names = new String[CHOOSABLE.size()];
        int checked = -1;
        for (int i = 0; i < CHOOSABLE.size(); i++) {
            names[i] = context.getString(audienceName(CHOOSABLE.get(i)));
            if (CHOOSABLE.get(i) == current.policy().threshold(field)) {
                checked = i;
            }
        }
        final String label = context.getString(labelOf(field)).toLowerCase(context.getResources()
                .getConfiguration().getLocales().get(0));
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.profile_audience_title, label))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    d.dismiss();
                    setAudience(field, CHOOSABLE.get(which));
                })
                .setNegativeButton(R.string.profile_cancel, null)
                .show();
    }

    /**
     * Stores one field's new value: what a dialog's Save or Prefer not to say does.
     *
     * @param field the field
     * @param value its new value
     */
    void setValue(final ProfileField field, final FieldValue value) {
        if (current != null) {
            save(current.profile().with(field, value), current.policy());
        }
    }

    /**
     * Stores who may see one field: what the audience dialog does.
     *
     * @param field the field
     * @param audience the least-trusted audience that may see it
     */
    void setAudience(final ProfileField field, final ProfileFieldPolicy.Audience audience) {
        if (current != null) {
            save(current.profile(), current.policy().with(field, audience));
        }
    }

    private void save(final LocalProfile profile, final ProfileFieldPolicy policy) {
        final ProfileSource.Editing next = new ProfileSource.Editing(profile, policy);
        // The next change builds on this one at once; the stored read below
        // then puts the screen on what was actually kept.
        current = next;
        worker.execute(() -> {
            final boolean stored = source.save(next);
            final Optional<ProfileSource.Editing> read = source.load();
            main.post(() -> {
                read.ifPresent(this::draw);
                if (!stored) {
                    status(R.string.profile_not_saved);
                }
            });
        });
    }

    private void preview(final ProfileFieldPolicy.Audience audience, final int title) {
        if (current == null) {
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(previewText(audience))
                .setPositiveButton(R.string.profile_preview_close, null)
                .show();
    }

    /**
     * What one audience sees, in words: the same ProfilePreview.derive the
     * sharing path uses. A declined field shows nothing, like an empty one.
     *
     * @param audience who is looking
     * @return one "Label: value" line per visible answered field, or the
     *     "nothing yet" sentence
     */
    String previewText(final ProfileFieldPolicy.Audience audience) {
        final Map<ProfileField, FieldValue> visible =
                ProfilePreview.derive(current.profile(), current.policy(), audience).visibleValues();
        final StringBuilder text = new StringBuilder();
        for (final ProfileField field : EDITED) {
            final FieldValue value = visible.get(field);
            if (value != null && value.kind() == FieldValue.Kind.STATED) {
                text.append(context.getString(labelOf(field))).append(": ").append(value.text()).append('\n');
            }
        }
        if (ProfilePreview.derive(current.profile(), current.policy(), audience).primaryMediaObjectId() != null) {
            text.append(context.getString(R.string.profile_preview_photo)).append('\n');
        }
        return text.length() == 0 ? context.getString(R.string.profile_preview_nothing) : text.toString().trim();
    }

    static int labelOf(final ProfileField field) {
        switch (field) {
            case ALIAS:
                return R.string.profile_field_alias;
            case AGE_BAND:
                return R.string.profile_field_age_band;
            case PRONOUNS:
                return R.string.profile_field_pronouns;
            case GENDER_IDENTITY:
                return R.string.profile_field_gender_identity;
            case ORIENTATION:
                return R.string.profile_field_orientation;
            case RELATIONSHIP_INTENT:
                return R.string.profile_field_relationship_intent;
            case INTERESTS:
                return R.string.profile_field_interests;
            case MEDIA:
                return R.string.profile_field_media;
            default:
                return R.string.profile_field_bio;
        }
    }

    private static int hintOf(final ProfileField field) {
        switch (field) {
            case ALIAS:
                return R.string.profile_hint_alias;
            case PRONOUNS:
                return R.string.profile_hint_pronouns;
            case GENDER_IDENTITY:
                return R.string.profile_hint_gender_identity;
            case ORIENTATION:
                return R.string.profile_hint_orientation;
            case RELATIONSHIP_INTENT:
                return R.string.profile_hint_relationship_intent;
            case INTERESTS:
                return R.string.profile_hint_interests;
            default:
                return R.string.profile_hint_bio;
        }
    }

    static int audienceName(final ProfileFieldPolicy.Audience audience) {
        switch (audience) {
            case NEARBY:
                return R.string.profile_audience_nearby;
            case REQUESTER:
                return R.string.profile_audience_requester;
            case VENUE:
                return R.string.profile_audience_venue;
            default:
                return R.string.profile_audience_match;
        }
    }

    /** @return the field rows' container, for tests */
    ViewGroup fields() {
        return root.findViewById(R.id.profile_fields);
    }
}

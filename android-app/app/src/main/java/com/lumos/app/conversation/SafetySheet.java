/*
 * Lumos - android-app/app / The conversation's safety sheet and its confirmations.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-001, SRS-MAT-009, SRS-UX-012, SRS-NFR-A11Y-003
 * ADRs: ADR-012
 * Threading: main
 * Notes: A bottom sheet with two actions, unmatch and block, then a
 * confirmation that says exactly what will happen: the chat is deleted from
 * this phone, they are not told, and it cannot be undone. The choice is only
 * carried out after the confirmation, through the callback, so the activity
 * stays the one place that touches the worker. Mute arrives with
 * notifications and report with CR-007; neither is offered before it works.
 */

package com.lumos.app.conversation;

import android.app.Dialog;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lumos.app.R;
import com.lumos.app.relay.ConversationSafety;
import java.util.function.Consumer;

/** The safety sheet for one conversation, and the confirmation it leads to. */
final class SafetySheet {

    private final AppCompatActivity activity;
    private final String name;
    private final Consumer<ConversationSafety.Ending> chosen;
    private final BottomSheetDialog sheet;
    private AlertDialog confirmation;

    private SafetySheet(final AppCompatActivity activity, final String name,
            final Consumer<ConversationSafety.Ending> chosen) {
        this.activity = activity;
        this.name = name;
        this.chosen = chosen;
        this.sheet = new BottomSheetDialog(activity);
    }

    /**
     * Opens the sheet.
     *
     * @param activity the conversation screen
     * @param name the name the header shows
     * @param chosen called with the confirmed choice, on the main thread
     * @return the open sheet
     */
    static SafetySheet show(final AppCompatActivity activity, final String name,
            final Consumer<ConversationSafety.Ending> chosen) {
        final SafetySheet s = new SafetySheet(activity, name, chosen);
        s.sheet.setContentView(R.layout.sheet_safety);
        s.<TextView>view(R.id.safety_title).setText(activity.getString(R.string.safety_title, name));
        s.<TextView>view(R.id.safety_note).setText(activity.getString(R.string.safety_note, name));
        s.view(R.id.safety_unmatch).setOnClickListener(v -> s.confirm(ConversationSafety.Ending.UNMATCH));
        s.view(R.id.safety_block).setOnClickListener(v -> s.confirm(ConversationSafety.Ending.BLOCK));
        s.sheet.show();
        return s;
    }

    private <T extends View> T view(final int id) {
        final T v = sheet.findViewById(id);
        if (v == null) {
            throw new IllegalStateException("the safety sheet layout lost a view");
        }
        return v;
    }

    /** @return the sheet's dialog, for the instrumented test */
    Dialog sheet() {
        return sheet;
    }

    /** @return the confirmation, once one is open; for the instrumented test */
    AlertDialog confirmation() {
        return confirmation;
    }

    private void confirm(final ConversationSafety.Ending how) {
        sheet.dismiss();
        final boolean block = how == ConversationSafety.Ending.BLOCK;
        confirmation = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(block ? R.string.safety_block_confirm_title
                        : R.string.safety_unmatch_confirm_title, name))
                .setMessage(block ? R.string.safety_block_confirm_body : R.string.safety_unmatch_confirm_body)
                .setPositiveButton(block ? R.string.safety_block : R.string.safety_unmatch,
                        (d, w) -> chosen.accept(how))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}

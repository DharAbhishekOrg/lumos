/*
 * Lumos - android-app/app / The launcher: onboarding, or straight to home once it is done.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-006, SRS-ONB-008, TRS-AND-010
 * ADRs: ADR-012
 * Threading: main, with the keystore and the database on a background executor
 * Notes: The Android half of the iPhone's onboarding screens (CS3b), in the
 * same words. On launch the install's state is read off the main thread: an
 * onboarded install opens the home tabs; anyone else gets the steps the
 * OnboardingService allows, in the SRS order - the age gate, then the four
 * documents (each readable in full), then the identity, made on this phone
 * with no sign-up. Under 18 ends on a screen with no way forward.
 *
 * The date picker starts on today, which does not pass, and Continue stays off
 * until a date has been chosen, so the default is never an answer someone can
 * tap through. The date is handed to the gate and kept by nothing here.
 * Visible Nearby is not touched (SRS-ONB-008). The onboarding in progress
 * lives in the application, so a rotation does not restart it.
 */

package com.lumos.app.onboarding;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.lumos.app.AppBoot;
import com.lumos.app.DeviceInstallation;
import com.lumos.app.LumosApplication;
import com.lumos.app.R;
import com.lumos.app.home.HomeActivity;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;

/** Onboarding, one step at a time; the app's launcher. */
public final class OnboardingActivity extends AppCompatActivity {

    private LumosApplication app;
    private OnboardingService service;
    private ExecutorService worker;
    private boolean dateChosen;
    private boolean failed;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (LumosApplication) getApplication();
        service = app.onboarding();
        worker = app.relayExecutor();
        setContentView(R.layout.activity_onboarding);
        applyInsets();
        ViewCompat.setAccessibilityHeading(findViewById(R.id.onboarding_title), true);
        final DatePicker picker = findViewById(R.id.age_picker);
        final LocalDate today = LocalDate.now();
        picker.setMaxDate(System.currentTimeMillis());
        picker.init(today.getYear(), today.getMonthValue() - 1, today.getDayOfMonth(), (view, y, m, d) -> {
            dateChosen = true;
            render();
        });
        bindDocument(R.id.doc_terms, LegalDocument.TERMS_OF_USE, R.string.consent_doc_terms);
        bindDocument(R.id.doc_privacy, LegalDocument.PRIVACY_NOTICE, R.string.consent_doc_privacy);
        bindDocument(R.id.doc_community, LegalDocument.COMMUNITY_RULES, R.string.consent_doc_community);
        bindDocument(R.id.doc_radio, LegalDocument.LOCAL_RADIO_DISCLOSURE, R.string.consent_doc_radio);
        findViewById(R.id.onboarding_action).setOnClickListener(view -> act());
        showWorking();
        worker.execute(() -> {
            LocalStore onboarded;
            try {
                onboarded = AppBoot.openOnboarded(this).orElse(null);
            } catch (IOException | GeneralSecurityException
                     | StoreException unreadable) {
                // An install that cannot be read is not an onboarded one: the
                // steps run again and make a fresh root (ADR-012).
                onboarded = null;
            }
            final LocalStore done = onboarded;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (done != null) {
                    app.onboardingCompleted(done);
                    openHome();
                } else {
                    render();
                }
            });
        });
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onboarding_root), (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void bindDocument(final int id, final LegalDocument document, final int title) {
        findViewById(id).setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(read(document))
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    private String read(final LegalDocument document) {
        try (InputStream in = getAssets().open(document.assetName())) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** The action button's meaning for the current step. */
    private void act() {
        switch (service.machine().state()) {
            case AGE_GATE:
                final DatePicker picker = findViewById(R.id.age_picker);
                service.declareAge(LocalDate.of(picker.getYear(), picker.getMonth() + 1, picker.getDayOfMonth()),
                        LocalDate.now());
                render();
                break;
            case LEGAL_CONSENT:
                try {
                    service.acceptDocuments(LegalDocuments.load(doc -> getAssets().open(doc.assetName())));
                } catch (IOException missing) {
                    // A build without its documents cannot take consent; stay here.
                    return;
                }
                render();
                break;
            case IDENTITY_CREATION:
                createIdentity();
                break;
            default:
                break;
        }
    }

    private void createIdentity() {
        failed = false;
        showWorking();
        worker.execute(() -> {
            LocalStore made;
            try {
                made = service.createIdentity(new DeviceInstallation(this));
            } catch (IOException | GeneralSecurityException
                     | StoreException | IllegalStateException notDone) {
                made = null;
            }
            final LocalStore done = made;
            runOnUiThread(() -> {
                if (done != null) {
                    app.onboardingCompleted(done);
                    openHome();
                } else if (!isFinishing() && !isDestroyed()) {
                    failed = true;
                    render();
                }
            });
        });
    }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }

    private void showWorking() {
        findViewById(R.id.onboarding_progress).setVisibility(View.VISIBLE);
        findViewById(R.id.onboarding_action).setVisibility(View.GONE);
    }

    /** Shows the step the machine is on. */
    private void render() {
        final OnboardingState state = service.machine().state();
        final TextView title = findViewById(R.id.onboarding_title);
        final TextView body = findViewById(R.id.onboarding_body);
        final MaterialButton action = findViewById(R.id.onboarding_action);
        findViewById(R.id.age_picker).setVisibility(state == OnboardingState.AGE_GATE ? View.VISIBLE : View.GONE);
        findViewById(R.id.consent_documents)
                .setVisibility(state == OnboardingState.LEGAL_CONSENT ? View.VISIBLE : View.GONE);
        findViewById(R.id.identity_notes)
                .setVisibility(state == OnboardingState.IDENTITY_CREATION ? View.VISIBLE : View.GONE);
        findViewById(R.id.onboarding_progress).setVisibility(View.GONE);
        action.setVisibility(View.VISIBLE);
        switch (state) {
            case AGE_GATE:
                title.setText(R.string.age_gate_title);
                body.setText(R.string.age_gate_body);
                action.setText(R.string.age_gate_continue);
                action.setEnabled(dateChosen);
                break;
            case BLOCKED_UNDER_AGE:
                title.setText(R.string.age_gate_blocked_title);
                body.setText(R.string.age_gate_blocked_body);
                action.setVisibility(View.GONE);
                break;
            case LEGAL_CONSENT:
                title.setText(R.string.consent_title);
                body.setText(R.string.consent_body);
                action.setText(R.string.consent_accept);
                action.setEnabled(true);
                break;
            case IDENTITY_CREATION:
            default:
                title.setText(R.string.identity_title);
                body.setText(failed ? R.string.identity_failed : R.string.identity_body);
                action.setText(failed ? R.string.identity_retry : R.string.identity_start);
                action.setEnabled(true);
                break;
        }
    }
}

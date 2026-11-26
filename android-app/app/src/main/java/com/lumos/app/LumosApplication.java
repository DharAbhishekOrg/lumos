/*
 * Lumos - android-app/app / The process entry point: the crypto backend first, then the relay plumbing.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003, SRS-RMT-003, TRS-TRN-012, SRS-UX-003
 * ADRs: ADR-001, ADR-012
 * Threading: main
 * Notes: Android creates the Application before any activity, service or
 * receiver, so installing libsodium here means no protocol code - HELLO
 * verification in the proximity service, X3DH, the ratchet - can ever reach
 * the JCA backend, which cannot run below API 33 (WP-05 S7d). It then makes
 * sure the periodic relay poll exists (it only ever runs with a network) and
 * owns the one serial executor every relay pass runs on. The chat runtime is
 * booted lazily on that executor, never on the main thread, and only once.
 * Midnight is the default appearance (CR-008); the setting that lets a person
 * choose Daylight or follow the system lands with the chat surface's settings
 * slice.
 */

package com.lumos.app;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import com.lumos.app.crypto.LibsodiumCryptoBackend;
import com.lumos.app.identity.LibsodiumIdentityCrypto;
import com.lumos.app.nearby.NearbyActivity;
import com.lumos.app.onboarding.OnboardingMachine;
import com.lumos.app.onboarding.OnboardingService;
import com.lumos.app.relay.ChatRuntime;
import com.lumos.app.relay.RelayJobs;
import com.lumos.app.store.LocalStore;
import com.lumos.protocol.wire.LumosCrypto;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The Lumos process. */
public final class LumosApplication extends Application {

    /** Midnight, the dark theme, unless the person chooses otherwise (CR-008). */
    public static final int DEFAULT_NIGHT_MODE = AppCompatDelegate.MODE_NIGHT_YES;

    private final ExecutorService relayExecutor = Executors.newSingleThreadExecutor();
    private final AppHost host = new AppHost();
    private OnboardingService onboarding;
    private ChatRuntime runtime;
    private boolean booted;

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(DEFAULT_NIGHT_MODE);
        LumosCrypto.install(LibsodiumCryptoBackend.load());
        // The composition root's host: entry stays closed until the launcher
        // (OnboardingActivity) has read this install's state off the main
        // thread, or onboarding has just completed (SRS-ONB-001).
        NearbyActivity.setHost(host);
        if (!BuildConfig.RELAY_BASE_URL.isEmpty()) {
            RelayJobs.ensurePeriodic(this);
        }
    }

    /** @return the one serial executor relay passes run on */
    /**
     * This process's onboarding, kept across the launcher's configuration
     * changes so a rotation never sends someone back to the age gate.
     *
     * @return the onboarding in progress
     */
    public synchronized OnboardingService onboarding() {
        if (onboarding == null) {
            onboarding = new OnboardingService(new OnboardingMachine(), LibsodiumIdentityCrypto.load(),
                    System::currentTimeMillis);
        }
        return onboarding;
    }

    /**
     * Records that onboarding completed: entry opens, Visible Nearby attaches
     * to the store, and the finished flow is dropped.
     *
     * @param store the install's open store
     */
    public synchronized void onboardingCompleted(final LocalStore store) {
        onboarding = null;
        host.attach(this, store);
        host.setOnboarded(true);
    }

    /**
     * The running app as the surfaces see it.
     *
     * @return the host
     */
    public AppHost host() {
        return host;
    }

    public ExecutorService relayExecutor() {
        return relayExecutor;
    }

    /**
     * The chat runtime, booted on first use. Call on the relay executor only.
     *
     * @return the runtime, or null when the relay is not configured or this install has not onboarded
     * @throws IOException when the key root file is unreadable
     * @throws GeneralSecurityException when the keystore refuses the KEK
     * @throws com.lumos.app.store.StoreException when the database cannot be opened
     */
    public synchronized ChatRuntime runtime()
            throws IOException, GeneralSecurityException, com.lumos.app.store.StoreException {
        if (!booted) {
            runtime = AppBoot.boot(this);
            booted = runtime != null;
        }
        return runtime;
    }
}

/*
 * Lumos - android-app/app / The process entry point: installs the crypto backend before anything else runs.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-001, TRS-CRY-003
 * ADRs: ADR-001
 * Threading: main
 * Notes: Android creates the Application before any activity, service or
 * receiver, so installing here means no protocol code - HELLO verification
 * in the proximity service, X3DH, the ratchet - can ever reach the JCA
 * backend, which cannot run below API 33 (WP-05 S7d). Loading libsodium is
 * a few milliseconds of native-library load; nothing else happens here.
 */

package com.lumos.app;

import android.app.Application;
import com.lumos.app.crypto.LibsodiumCryptoBackend;
import com.lumos.protocol.wire.LumosCrypto;

/** The Lumos process. */
public final class LumosApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LumosCrypto.install(LibsodiumCryptoBackend.load());
    }
}

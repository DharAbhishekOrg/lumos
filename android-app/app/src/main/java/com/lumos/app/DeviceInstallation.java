/*
 * Lumos - android-app/app / Where onboarding's last step puts this phone's keys and store.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-006, TRS-AND-007, SRS-DAT-002
 * ADRs: ADR-010, ADR-012
 * Threading: background (the keystore and the database)
 * Notes: The production OnboardingService.Installation: the KEK in the Android
 * Keystore under the app's alias (StrongBox first), the key root file in the
 * no-backup directory, and the store through AppBoot.openStore, so the files
 * onboarding writes are exactly the ones AppBoot reads on every later launch.
 * A retry after a failed last step regenerates the KEK under the same alias;
 * no rows were written under the old one.
 */

package com.lumos.app;

import android.content.Context;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.keystore.AndroidKekStore;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.onboarding.OnboardingService;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import java.io.IOException;
import java.security.GeneralSecurityException;

/** This phone's installation. */
public final class DeviceInstallation implements OnboardingService.Installation {

    private final Context context;

    /**
     * @param context any context of the app
     */
    public DeviceInstallation(final Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public KekStore createKek() throws GeneralSecurityException {
        return AndroidKekStore.create(AppBoot.KEK_ALIAS);
    }

    @Override
    public void persistRoot(final KeyRootFile.Root root) throws IOException {
        KeyRootFile.write(AppBoot.keyRootFile(context), root);
    }

    @Override
    public LocalStore openStore(final DataKeyHierarchy keys) throws StoreException, GeneralSecurityException {
        return AppBoot.openStore(context, keys, System::currentTimeMillis);
    }
}

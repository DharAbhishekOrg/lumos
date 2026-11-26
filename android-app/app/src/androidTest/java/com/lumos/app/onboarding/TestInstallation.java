/*
 * Lumos - android-app/app / The device installation, pointed at a test's own Room database and KEK alias.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-006
 * ADRs: ADR-012
 * Threading: any
 * Notes: The real pieces (the Android Keystore, the key root file format, Room
 * through the sealed store) with a test's alias and database, so the
 * instrumented onboarding tests run the production order without touching the
 * app's own install. The created KEK is kept for the test to destroy.
 */

package com.lumos.app.onboarding;

import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.keystore.AndroidKekStore;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import com.lumos.app.store.room.LumosRoomDatabase;
import com.lumos.app.store.room.RoomSqlDatabase;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/** A real installation with a test's alias, database and root file. */
final class TestInstallation implements OnboardingService.Installation {

    private final String alias;
    private final LumosRoomDatabase room;
    private final File rootFile;
    private final long now;
    private AndroidKekStore kek;

    TestInstallation(final String alias, final LumosRoomDatabase room, final File rootFile, final long now) {
        this.alias = alias;
        this.room = room;
        this.rootFile = rootFile;
        this.now = now;
    }

    /** @return the KEK this installation created, or null before the last step */
    AndroidKekStore kek() {
        return kek;
    }

    @Override
    public KekStore createKek() throws GeneralSecurityException {
        kek = AndroidKekStore.create(alias);
        return kek;
    }

    @Override
    public void persistRoot(final KeyRootFile.Root root) throws IOException {
        KeyRootFile.write(rootFile, root);
    }

    @Override
    public LocalStore openStore(final DataKeyHierarchy keys) throws StoreException, GeneralSecurityException {
        return LocalStore.open(RoomSqlDatabase.wrapping(room), keys.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                () -> Long.MAX_VALUE, () -> now);
    }
}

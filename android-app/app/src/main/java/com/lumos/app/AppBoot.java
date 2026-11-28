/*
 * Lumos - android-app/app / Opening an onboarded install: key root, KEK, data keys, store, identity, runtime.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, TRS-LOC-002, TRS-AND-007, SRS-DAT-002, SRS-NBY-005
 * ADRs: ADR-001, ADR-003, ADR-010, ADR-012
 * Threading: background (never the main thread: it opens the database and the keystore)
 * Notes: ADR-012's boot order. The key root file names the KEK's protection
 * tier and holds the wrapped data root; the KEK unwraps the root; the root
 * derives the database key; the store yields the identity; the identity is
 * unlocked for the chat. Any missing step means "not onboarded on this
 * install" and the boot answers null rather than inventing state. A relay
 * base URL is per build flavor; an empty one means the relay is not
 * configured for this build (until the owner's staging deploy, WP-05 S8) and
 * the chat runs nearby only.
 */

package com.lumos.app;

import android.content.Context;
import com.lumos.app.ble.NearbyIdentity;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.data.KeyRootFile;
import com.lumos.app.identity.ChatIdentity;
import com.lumos.app.identity.IdentityCrypto;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.identity.LibsodiumIdentityCrypto;
import com.lumos.app.keystore.AndroidKekStore;
import com.lumos.app.keystore.KekStore;
import com.lumos.app.media.MediaObjectStore;
import com.lumos.app.relay.ChatRuntime;
import com.lumos.app.relay.UrlConnectionTransport;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.StoreException;
import com.lumos.app.store.room.LumosRoomDatabase;
import com.lumos.app.store.room.RoomSqlDatabase;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

/** Boots the chat runtime of an onboarded install. */
public final class AppBoot {

    /** The keystore alias of the app's KEK; onboarding creates it under this name (ADR-012). */
    public static final String KEK_ALIAS = "lumos.kek.v1";

    /** The encrypted media store's directory, under the no-backup files directory. */
    static final String MEDIA_DIRECTORY = "media";

    private AppBoot() { }

    /**
     * Where onboarding writes the key root and the boot reads it.
     *
     * @param context any context
     * @return the file in the no-backup directory
     */
    public static File keyRootFile(final Context context) {
        return new File(context.getNoBackupFilesDir(), KeyRootFile.NAME);
    }

    /**
     * Boots the runtime.
     *
     * @param context the application context
     * @return the runtime, or null when the relay is not configured or this install has not onboarded
     * @throws IOException when the key root file is unreadable
     * @throws GeneralSecurityException when the keystore refuses the KEK or the identity does not unwrap
     * @throws StoreException when the database cannot be opened
     */
    public static ChatRuntime boot(final Context context) throws IOException, GeneralSecurityException, StoreException {
        final String base = BuildConfig.RELAY_BASE_URL;
        if (base.isEmpty()) {
            return null;
        }
        final Optional<KeyRootFile.Root> root = KeyRootFile.read(keyRootFile(context));
        if (!root.isPresent()) {
            return null;
        }
        final KekStore kek = AndroidKekStore.open(KEK_ALIAS, root.get().protection());
        final DataKeyHierarchy keys = DataKeyHierarchy.restore(kek, root.get().wrappedRoot());
        final LocalStore.Clock clock = System::currentTimeMillis;
        final LocalStore store = openStore(context, keys, clock);
        final Optional<IdentityKeyRecord> record = new IdentityRepository(store).load();
        if (!record.isPresent()) {
            return null;
        }
        final IdentityCrypto crypto = LibsodiumIdentityCrypto.load();
        final ChatIdentity identity = ChatIdentity.unlock(record.get(), kek, crypto);
        return ChatRuntime.assemble(store, identity, crypto, UrlConnectionTransport.to(new URL(base)), clock,
                new SecureRandom());
    }

    /**
     * Opens the install's store under a data-key hierarchy. The one place the
     * app's store is opened, so onboarding writes the database the boot reads.
     *
     * @param context any context of the app
     * @param keys the data-key hierarchy
     * @param clock the UTC clock
     * @return the opened store
     * @throws StoreException if the store cannot open
     * @throws GeneralSecurityException if the database key cannot be derived
     */
    public static LocalStore openStore(final Context context, final DataKeyHierarchy keys,
            final LocalStore.Clock clock) throws StoreException, GeneralSecurityException {
        final File dir = context.getFilesDir();
        return LocalStore.open(RoomSqlDatabase.wrapping(LumosRoomDatabase.open(context)),
                keys.deriveKey(DataKeyHierarchy.DataDomain.DATABASE), dir::getUsableSpace, clock);
    }

    /**
     * Opens the onboarded install's store, whatever the relay: the key root,
     * the KEK, the store, and an identity in it.
     *
     * @param context any context of the app
     * @return the store, or empty when this install has not finished onboarding
     * @throws IOException if the key root file cannot be read
     * @throws StoreException if the store cannot open
     * @throws GeneralSecurityException if the KEK or the data key fails
     */
    public static Optional<LocalStore> openOnboarded(final Context context)
            throws IOException, StoreException, GeneralSecurityException {
        final Optional<KeyRootFile.Root> root = KeyRootFile.read(keyRootFile(context));
        if (!root.isPresent()) {
            return Optional.empty();
        }
        final KekStore kek = AndroidKekStore.open(KEK_ALIAS, root.get().protection());
        final LocalStore store = openStore(context, DataKeyHierarchy.restore(kek, root.get().wrappedRoot()),
                System::currentTimeMillis);
        return new IdentityRepository(store).exists() ? Optional.of(store) : Optional.empty();
    }

    /**
     * Opens the encrypted media store (profile photos) under the install's
     * MEDIA_STORE data key. It lives in the no-backup directory, like the
     * key root, so a cloud backup never carries photos (SRS-PRO-004).
     *
     * @param context any context of the app
     * @return the store, or empty when this install has no key root yet
     * @throws IOException if the key root file or the directory fails
     * @throws GeneralSecurityException if the KEK or the data key fails
     */
    public static Optional<MediaObjectStore> openMediaStore(final Context context)
            throws IOException, GeneralSecurityException {
        final Optional<KeyRootFile.Root> root = KeyRootFile.read(keyRootFile(context));
        if (!root.isPresent()) {
            return Optional.empty();
        }
        final KekStore kek = AndroidKekStore.open(KEK_ALIAS, root.get().protection());
        final byte[] key = DataKeyHierarchy.restore(kek, root.get().wrappedRoot())
                .deriveKey(DataKeyHierarchy.DataDomain.MEDIA_STORE);
        try {
            return Optional.of(new MediaObjectStore(
                    new File(context.getNoBackupFilesDir(), MEDIA_DIRECTORY).toPath(), key));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Unlocks this install's identity for nearby discovery, whatever the
     * relay: the key root, the KEK, the stored identity, then the signer.
     * The unlocked identity lives as long as the process, like the chat's.
     *
     * @param context any context of the app
     * @param store the install's open store
     * @return the HELLO identity, or empty when this install has not finished onboarding
     * @throws IOException if the key root file cannot be read
     * @throws StoreException if the identity cannot be read
     * @throws GeneralSecurityException if the KEK or the identity does not unwrap
     */
    public static Optional<NearbyIdentity> nearbyIdentity(final Context context, final LocalStore store)
            throws IOException, StoreException, GeneralSecurityException {
        final Optional<KeyRootFile.Root> root = KeyRootFile.read(keyRootFile(context));
        final Optional<IdentityKeyRecord> record = new IdentityRepository(store).load();
        if (!root.isPresent() || !record.isPresent()) {
            return Optional.empty();
        }
        final KekStore kek = AndroidKekStore.open(KEK_ALIAS, root.get().protection());
        final ChatIdentity identity = ChatIdentity.unlock(record.get(), kek, LibsodiumIdentityCrypto.load());
        return Optional.of(new NearbyIdentity(record.get(), identity::sign));
    }
}

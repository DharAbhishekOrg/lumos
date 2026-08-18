/*
 * Lumos - android-app/app / Recovery setup, on the shipping crypto and a real store.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: RecoveryFlowTest proves the S4c flow on the JVM with JDK primitives,
 * because Lazysodium ships a native library no JVM host here can load. This is
 * the same assemble-seal-open-decode path through the REAL binding - libsodium
 * Argon2id and ChaCha20-Poly1305, ADR-001's suite - over Room on an actual
 * Android runtime.
 *
 * WHAT IS DELIBERATELY NOT HERE: the restore half. Restore generates a fresh
 * identity (SRS-ONB-007), and identity generation cannot run on Android at all
 * - the platform JCA has no Ed25519 KeyPairGenerator, at any API level, which
 * this slice discovered by running it and which
 * PlatformKeyAlgorithmInstrumentedTest now records as a tripwire. Restore is
 * therefore JVM-proven and device-unproven, and saying so is better than a
 * device test that quietly skips the interesting half.
 *
 * The identity used below is a SYNTHETIC record built from fixed bytes rather
 * than a generated one. That is not a workaround: the ADR-003 assertion is
 * "these bytes must not appear in the export", and bytes nobody generated make
 * that assertion sharper, not weaker.
 *
 * A note for whoever reads a timeout rather than a failure: the derivation
 * really does cost 64 MiB and three passes on the device, per seal and per
 * open, so these are seconds rather than milliseconds by design.
 */

package com.lumos.app.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.keystore.KeyProtection;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.room.LumosRoomDatabase;
import com.lumos.app.store.room.RoomSqlDatabase;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The recovery export on the shipping primitives and a real database. */
@RunWith(AndroidJUnit4.class)
public class RecoveryFlowInstrumentedTest {

    /** A distinctive alias, so finding it in the opened bundle means something. */
    private static final String ALIAS = "a-distinctive-alias-nobody-else-would-write";

    /** A passphrase with a non-ASCII character, which S4b found traps naive bindings. */
    private static final String PASSPHRASE = "café correct horse battery staple";

    /** Raw key length for both Ed25519 and X25519. */
    private static final int RAW_KEY_BYTES = 32;

    /** Ed25519 signature length. */
    private static final int SIGNATURE_BYTES = 64;

    private static final long NOW = 1_755_561_600_000L;

    private RecoveryRecord record;
    private LocalStore store;

    @Before
    public void openTheRealBindingAndAFreshStore() throws Exception {
        record = new RecoveryRecord(LibsodiumRecoveryCrypto.load());

        final Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(LumosRoomDatabase.DATABASE_FILE);
        final LumosRoomDatabase room = LumosRoomDatabase.open(context);
        final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(SoftwareKekStore.create());
        store = LocalStore.open(RoomSqlDatabase.wrapping(room),
                hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                () -> Long.MAX_VALUE, () -> NOW);
    }

    private static byte[] passphrase() {
        return PASSPHRASE.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A synthetic identity record whose bytes are recognisable in a haystack.
     *
     * @return a record built from fixed, distinctive bytes
     */
    private static IdentityKeyRecord syntheticIdentity() {
        final byte[] ed = filled(RAW_KEY_BYTES, (byte) 0xA1);
        final byte[] dh = filled(RAW_KEY_BYTES, (byte) 0xB2);
        final byte[] signature = filled(SIGNATURE_BYTES, (byte) 0xC3);
        final byte[] wrapped = filled(RAW_KEY_BYTES * 3, (byte) 0xD4);
        return new IdentityKeyRecord(ed, dh, signature, wrapped,
                KeyProtection.SOFTWARE_FALLBACK);
    }

    /**
     * An array of one repeated byte.
     *
     * @param length how many
     * @param value the byte
     * @return the array
     */
    private static byte[] filled(final int length, final byte value) {
        final byte[] out = new byte[length];
        Arrays.fill(out, value);
        return out;
    }

    /**
     * Writes the content a device would have after onboarding and profile edit.
     *
     * @return the blocked peer's identifier, for the block-list assertions
     * @throws Exception on store failure
     */
    private byte[] populate() throws Exception {
        new IdentityRepository(store).replace(syntheticIdentity());
        store.put(RecordKind.LOCAL_PROFILE, "self",
                LocalStore.payloadOf("lumos-profile/1\nfield\tALIAS\tTEXT\t" + ALIAS), null);
        store.put(RecordKind.PROFILE_FIELD_POLICY, "self",
                LocalStore.payloadOf("lumos-policy/1\n"), null);
        final byte[] blocked = LocalStore.payloadOf("peer-identifier-0xfeed");
        store.put(RecordKind.BLOCK_ENTRY, "block-1", blocked, blocked);
        store.put(RecordKind.DISCOVERY_VISIBILITY, "self",
                LocalStore.payloadOf("lumos-visibility/1\noff\n0\n"), null);
        return blocked;
    }

    @Test
    public void anExportSealedByLibsodiumOpensBackToTheSameContent() throws Exception {
        populate();
        final byte[] sealed = new RecoverySetup(store, record, () -> NOW).export(passphrase());
        final RecoveryBundle bundle =
                RecoveryBundleCodec.decode(record.open(passphrase(), sealed));

        assertEquals(NOW, bundle.exportedAtUtcMillis());
        assertEquals(4, bundle.rowCount());
        assertEquals(List.of(RecoverySection.PROFILE, RecoverySection.PROFILE_FIELD_POLICY,
                RecoverySection.BLOCK_LIST, RecoverySection.PREFERENCES),
                bundle.filledSections());
        assertEquals("SRS-ONB-007 names five things and two have no feature yet",
                List.of(RecoverySection.MATCH_ROSTER, RecoverySection.CONVERSATION_HISTORY),
                bundle.unavailableSections());

        final String profile = new String(
                bundle.items(RecoverySection.PROFILE).get(0).content(), StandardCharsets.UTF_8);
        assertTrue("the profile came back", profile.contains(ALIAS));
        assertEquals("the block list is the section users most need back",
                1, bundle.items(RecoverySection.BLOCK_LIST).size());
    }

    @Test
    public void aLibsodiumSealedExportCarriesNoIdentityMaterial() throws Exception {
        final IdentityKeyRecord identity = syntheticIdentity();
        populate();

        final byte[] sealed = new RecoverySetup(store, record, () -> NOW).export(passphrase());
        final byte[] plaintext = record.open(passphrase(), sealed);

        for (final byte[] secret : List.of(identity.wrappedPrivateKeys(),
                identity.ed25519Public(), identity.x25519Public(),
                identity.bindingSignature())) {
            assertFalse("ADR-003: the sealed record must carry no identity material",
                    contains(sealed, secret));
            assertFalse("ADR-003: nor must its content", contains(plaintext, secret));
        }
    }

    @Test
    public void aWrongPassphraseIsRefusedIndistinguishablyByTheRealBinding() throws Exception {
        populate();
        final byte[] sealed = new RecoverySetup(store, record, () -> NOW).export(passphrase());
        try {
            record.open("not the passphrase".getBytes(StandardCharsets.UTF_8), sealed);
            throw new AssertionError("a wrong passphrase opened the record");
        } catch (RecoveryException expected) {
            assertEquals("the failure says nothing about which cause it was (TRS-CRY-013)",
                    "record could not be opened", expected.getMessage());
        }
    }

    @Test
    public void twoExportsOfTheSameContentDifferAndBothOpen() throws Exception {
        populate();
        final RecoverySetup setup = new RecoverySetup(store, record, () -> NOW);
        final byte[] first = setup.export(passphrase());
        final byte[] second = setup.export(passphrase());

        assertFalse("a fresh salt and nonce per export, or two exports would leak that "
                + "the content did not change", Arrays.equals(first, second));
        for (final byte[] sealed : List.of(first, second)) {
            final RecoveryBundle bundle =
                    RecoveryBundleCodec.decode(record.open(passphrase(), sealed));
            assertTrue(new String(bundle.items(RecoverySection.PROFILE).get(0).content(),
                    StandardCharsets.UTF_8).contains(ALIAS));
        }
    }

    /**
     * Whether one byte sequence appears inside another.
     *
     * @param haystack the bytes to search
     * @param needle what must not be found
     * @return true when the needle occurs
     */
    private static boolean contains(final byte[] haystack, final byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) {
            return false;
        }
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
                return true;
            }
        }
        return false;
    }
}

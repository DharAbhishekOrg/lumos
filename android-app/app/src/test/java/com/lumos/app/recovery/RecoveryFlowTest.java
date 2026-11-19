/*
 * Lumos - android-app/app / Recovery setup and restore, over the S4b record.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-007, SRS-DAT-004, SRS-IDN-002
 * ADRs: ADR-003, ADR-008
 * Threading: any
 * Notes: The flow, not the format. The format is S4b's and is proven by the
 * cross-language vectors; what is new in S4c is what goes into the record, what
 * comes out, and what restore does to the identity. The primitives here are the
 * JDK ones - see JvmRecoveryCrypto for exactly what that does and does not
 * prove - and RecoverySetupInstrumentedTest runs the same flow through the real
 * libsodium binding on a device.
 *
 * Two of these tests are the requirement itself rather than a property of it:
 *
 * - theExportCarriesNoKeyMaterial is ADR-003. It writes a real identity, makes
 *   a real export, and looks for the identity's actual bytes in it.
 * - restoreYieldsAFreshIdentity is the SRS-ONB-007 sentence "restore creates a
 *   new cryptographic identity", checked by comparing public keys rather than
 *   by trusting that the call was made.
 */

package com.lumos.app.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.identity.JcaIdentityCrypto;
import com.lumos.app.Fixtures;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.IdentityRepository;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Setting up recovery, and restoring from it. */
final class RecoveryFlowTest {

    private static final long EXPORTED_AT = 1_755_561_600_000L;
    private static final String ALIAS = "a-distinctive-alias-nobody-else-would-write";
    private static final String BLOCKED_PEER = "peer-identifier-0xfeed";

    private static byte[] passphrase() {
        return "correct horse battery staple".getBytes(StandardCharsets.UTF_8);
    }

    private static RecoveryRecord record() {
        return new RecoveryRecord(new JvmRecoveryCrypto());
    }

    /**
     * Fills the store with the content a real device would have after onboarding.
     *
     * @param stack the standing store
     * @return the identity that was created
     * @throws Exception on key or store failure
     */
    private static IdentityKeyRecord populate(final Fixtures.Stack stack) throws Exception {
        final IdentityKeyRecord identity = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), stack.kek());
        new IdentityRepository(stack.store()).replace(identity);
        stack.store().put(RecordKind.LOCAL_PROFILE, "self",
                LocalStore.payloadOf("lumos-profile/1\nfield\tALIAS\tTEXT\t" + ALIAS), null);
        stack.store().put(RecordKind.PROFILE_FIELD_POLICY, "self",
                LocalStore.payloadOf("lumos-policy/1\n"), null);
        final byte[] blocked = LocalStore.payloadOf(BLOCKED_PEER);
        stack.store().put(RecordKind.BLOCK_ENTRY, "block-1", blocked, blocked);
        stack.store().put(RecordKind.DISCOVERY_VISIBILITY, "self",
                LocalStore.payloadOf("lumos-visibility/1\noff\n0\n"), null);
        return identity;
    }

    @Test
    void assembleCarriesTheContentSectionsAndNamesTheOnesItCannot() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            populate(stack);
            final RecoveryBundle bundle =
                    new RecoverySetup(stack.store(), record(), () -> EXPORTED_AT).assemble();

            assertEquals(EXPORTED_AT, bundle.exportedAtUtcMillis());
            assertEquals(List.of(RecoverySection.PROFILE, RecoverySection.PROFILE_FIELD_POLICY,
                    RecoverySection.BLOCK_LIST, RecoverySection.PREFERENCES),
                    bundle.filledSections());
            assertEquals(List.of(RecoverySection.MATCH_ROSTER,
                    RecoverySection.CONVERSATION_HISTORY), bundle.unavailableSections(),
                    "SRS-ONB-007 names five things and two have no feature yet; the "
                            + "bundle must say which rather than look complete");
            assertEquals(4, bundle.rowCount());
        }
    }

    @Test
    void theExportCarriesNoKeyMaterial() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityKeyRecord identity = populate(stack);
            final byte[] sealed = new RecoverySetup(stack.store(), record(), () -> EXPORTED_AT)
                    .export(passphrase());

            // The sealed bytes, and the plaintext inside them.
            final byte[] plaintext = record().open(passphrase(), sealed);
            for (final byte[] secret : List.of(identity.wrappedPrivateKeys(),
                    identity.ed25519Public(), identity.x25519Public(),
                    identity.bindingSignature())) {
                assertFalse(contains(sealed, secret),
                        "ADR-003: identity material must not be in the sealed record");
                assertFalse(contains(plaintext, secret),
                        "ADR-003: identity material must not be in the record's content");
            }
            // And the identity table is not a section at all, so it cannot become
            // one by somebody adding a row to it.
            for (final RecoverySection section : RecoverySection.values()) {
                assertNotEquals(RecordKind.IDENTITY_KEY_RECORD, section.kind(),
                        section + " reads the identity table; ADR-003 forbids exporting it");
            }
        }
    }

    @Test
    void theExportedContentIsActuallyInThere() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            populate(stack);
            final byte[] sealed = new RecoverySetup(stack.store(), record(), () -> EXPORTED_AT)
                    .export(passphrase());
            final byte[] plaintext = record().open(passphrase(), sealed);
            final RecoveryBundle bundle = RecoveryBundleCodec.decode(plaintext);

            final String profile = new String(
                    bundle.items(RecoverySection.PROFILE).get(0).content(),
                    StandardCharsets.UTF_8);
            assertTrue(profile.contains(ALIAS), "the profile came back");
            assertEquals(1, bundle.items(RecoverySection.BLOCK_LIST).size(),
                    "the block list is the section users most need back");
        }
    }

    @Test
    void theExportZeroesThePassphraseItWasGiven() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            populate(stack);
            final byte[] secret = passphrase();
            new RecoverySetup(stack.store(), record(), () -> EXPORTED_AT).export(secret);
            assertArrayEquals(new byte[secret.length], secret,
                    "the caller's passphrase buffer must not survive the call");
        }
    }

    @Test
    void restoreYieldsAFreshIdentity() throws Exception {
        final byte[] sealed;
        final byte[] oldKeyOnTheExportingDevice;
        try (Fixtures.Stack source = Fixtures.openInMemory()) {
            oldKeyOnTheExportingDevice = populate(source).ed25519Public();
            sealed = new RecoverySetup(source.store(), record(), () -> EXPORTED_AT)
                    .export(passphrase());
        }

        // A different device, with its own identity already on it.
        try (Fixtures.Stack target = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(target.store());
            final byte[] before = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), target.kek()).ed25519Public();
            identities.replace(IdentityKeyGenerator.generate(new JcaIdentityCrypto(), target.kek()));
            final byte[] actuallyBefore = identities.load().orElseThrow().ed25519Public();

            final RecoveryRestore.RestoreResult result =
                    new RecoveryRestore(target.store(), record(), identities, target.kek(), new JcaIdentityCrypto())
                            .restore(passphrase(), sealed);

            assertTrue(result.identityIsNew(), "SRS-ONB-007: restore creates a new identity");
            assertArrayEquals(actuallyBefore, result.previousEd25519Public());
            assertFalse(Arrays.equals(actuallyBefore, result.newEd25519Public()));
            assertFalse(Arrays.equals(oldKeyOnTheExportingDevice, result.newEd25519Public()),
                    "and it is not the exporting device's identity either - the record "
                            + "never contained it, so it cannot be");
            assertFalse(Arrays.equals(before, result.newEd25519Public()));

            final IdentityKeyRecord now = identities.load().orElseThrow();
            assertArrayEquals(result.newEd25519Public(), now.ed25519Public());
            assertTrue(IdentityKeyGenerator.verifyBinding(new JcaIdentityCrypto(), now.ed25519Public(),
                    now.x25519Public(), now.bindingSignature()),
                    "the restored device's identity is a properly bound ADR-008 identity");
        }
    }

    @Test
    void restoreOntoAnEmptyDeviceBringsTheContentBack() throws Exception {
        final byte[] sealed;
        try (Fixtures.Stack source = Fixtures.openInMemory()) {
            populate(source);
            sealed = new RecoverySetup(source.store(), record(), () -> EXPORTED_AT)
                    .export(passphrase());
        }
        try (Fixtures.Stack target = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(target.store());
            final RecoveryRestore.RestoreResult result =
                    new RecoveryRestore(target.store(), record(), identities, target.kek(), new JcaIdentityCrypto())
                            .restore(passphrase(), sealed);

            assertEquals(4, result.restoredRows());
            assertTrue(result.identityIsNew(), "a device that had none now has one");
            assertEquals(1, target.store().count(RecordKind.LOCAL_PROFILE));
            assertEquals(1, target.store().count(RecordKind.BLOCK_ENTRY));

            final String profile = new String(
                    target.store().get(RecordKind.LOCAL_PROFILE, "self").orElseThrow(),
                    StandardCharsets.UTF_8);
            assertTrue(profile.contains(ALIAS));

            // The blind index was re-derived on this device rather than carried
            // in the file, so the block list is queryable again under this
            // device's own key.
            assertEquals(1, target.store().findByNaturalKey(RecordKind.BLOCK_ENTRY,
                    LocalStore.payloadOf(BLOCKED_PEER)).size(),
                    "a restored block entry must still answer 'is this peer blocked?'");
        }
    }

    @Test
    void restoreTellsTheUserWhatCouldNotBeInTheFile() throws Exception {
        final byte[] sealed;
        try (Fixtures.Stack source = Fixtures.openInMemory()) {
            populate(source);
            sealed = new RecoverySetup(source.store(), record(), () -> EXPORTED_AT)
                    .export(passphrase());
        }
        try (Fixtures.Stack target = Fixtures.openInMemory()) {
            final RecoveryRestore.RestoreResult result = new RecoveryRestore(target.store(),
                    record(), new IdentityRepository(target.store()), target.kek(),
                    new JcaIdentityCrypto())
                    .restore(passphrase(), sealed);
            assertEquals(List.of(RecoverySection.MATCH_ROSTER,
                    RecoverySection.CONVERSATION_HISTORY), result.sectionsNotInTheFile(),
                    "'they were never in there' and 'they are lost' must be "
                            + "distinguishable to the person reading the screen");
        }
    }

    @Test
    void aWrongPassphraseRestoresNothingAndChangesNoIdentity() throws Exception {
        final byte[] sealed;
        try (Fixtures.Stack source = Fixtures.openInMemory()) {
            populate(source);
            sealed = new RecoverySetup(source.store(), record(), () -> EXPORTED_AT)
                    .export(passphrase());
        }
        try (Fixtures.Stack target = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(target.store());
            identities.replace(IdentityKeyGenerator.generate(new JcaIdentityCrypto(), target.kek()));
            final byte[] before = identities.load().orElseThrow().ed25519Public();

            final RecoveryRestore restore = new RecoveryRestore(target.store(), record(),
                    identities, target.kek(), new JcaIdentityCrypto());
            assertThrows(RecoveryException.class, () -> restore.restore(
                    "wrong passphrase entirely".getBytes(StandardCharsets.UTF_8), sealed));

            assertArrayEquals(before, identities.load().orElseThrow().ed25519Public(),
                    "a failed restore must not have replaced the identity on the way to "
                            + "failing - the record is opened before anything is touched");
            assertEquals(0, target.store().count(RecordKind.LOCAL_PROFILE));
        }
    }

    @Test
    void aBundleFromANewerBuildIsRefusedRatherThanPartlyRestored() {
        final byte[] future = ("lumos-recovery-bundle/1\nexported_at\t1\n"
                + "row\tsomething_new\tid\tAAAA\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> RecoveryBundleCodec.decode(future),
                "restoring three of four sections and reporting success is worse than "
                        + "refusing");

        final byte[] unknownTag = ("lumos-recovery-bundle/1\nexported_at\t1\n"
                + "attachment\tx\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> RecoveryBundleCodec.decode(unknownTag));

        final byte[] wrongVersion = "lumos-recovery-bundle/2\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> RecoveryBundleCodec.decode(wrongVersion));
    }

    @Test
    void twoExportsOfTheSameContentDifferButBothOpen() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            populate(stack);
            final RecoverySetup setup =
                    new RecoverySetup(stack.store(), record(), () -> EXPORTED_AT);
            final byte[] first = setup.export(passphrase());
            final byte[] second = setup.export(passphrase());
            assertFalse(Arrays.equals(first, second),
                    "a fresh salt and nonce per export, or two exports would leak that "
                            + "the content did not change");
            assertTrue(aliasIn(first), "and the first still opens to the same content");
            assertTrue(aliasIn(second), "as does the second");
        }
    }

    /**
     * Opens a sealed export and reports whether the profile alias is in it.
     *
     * <p>Through the bundle codec rather than by searching the plaintext: the
     * row contents are Base64 inside the bundle, so a substring search on the
     * opened bytes would look for the alias where it is not written.
     *
     * @param sealed the record bytes
     * @return true when the profile section carries the alias
     * @throws RecoveryException if the record does not open
     */
    private static boolean aliasIn(final byte[] sealed) throws RecoveryException {
        final RecoveryBundle bundle =
                RecoveryBundleCodec.decode(record().open(passphrase(), sealed));
        for (final RecoveryBundle.Item item : bundle.items(RecoverySection.PROFILE)) {
            if (new String(item.content(), StandardCharsets.UTF_8).contains(ALIAS)) {
                return true;
            }
        }
        return false;
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

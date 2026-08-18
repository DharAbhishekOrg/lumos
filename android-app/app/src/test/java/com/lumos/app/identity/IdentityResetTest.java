/*
 * Lumos - android-app/app / Identity reset, and the warning that precedes it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-003, SRS-IDN-001
 * ADRs: ADR-003, ADR-008, ADR-010
 * Threading: any
 * Notes: SRS-IDN-003 has two testable halves and both are here: the reset is
 * EXPLICIT (it cannot happen without a confirmation that cannot be forged) and
 * it carries a CLEAR WARNING (which is a computed value, so it can be asserted
 * rather than eyeballed).
 *
 * The kept() assertions matter as much as the lost() ones. A warning that lists
 * only losses pushes a frightened user towards emergency wipe, which destroys
 * far more than they wanted - so "your profile and your block list stay" is a
 * safety property, not reassurance.
 */

package com.lumos.app.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.safety.DeliberateConfirmation;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Resetting the device identity. */
final class IdentityResetTest {

    private static final String PHRASE = "new identity";
    private static final long NOW = 1_755_561_600_000L;

    private static DeliberateConfirmation.Confirmation resetConfirmation() throws Exception {
        return DeliberateConfirmation
                .issue(DeliberateConfirmation.Action.IDENTITY_RESET, PHRASE, NOW)
                .confirm(PHRASE, NOW + 1);
    }

    @Test
    void theIdentityIsReplacedAndTheOldPublicKeyIsGone() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(stack.store());
            identities.replace(IdentityKeyGenerator.generate(stack.kek()));
            final IdentityKeyRecord before = identities.load().orElseThrow();

            final IdentityReset.ResetResult result =
                    new IdentityReset(identities, stack.kek()).reset(resetConfirmation());

            assertTrue(result.identityIsNew());
            assertArrayEquals(before.ed25519Public(), result.previousEd25519Public());
            assertFalse(Arrays.equals(before.ed25519Public(), result.newEd25519Public()));
            assertFalse(Arrays.equals(before.x25519Public(),
                    identities.load().orElseThrow().x25519Public()),
                    "both halves of the ADR-008 identity are new, not only the signing key");
            assertFalse(Arrays.equals(before.wrappedPrivateKeys(),
                    identities.load().orElseThrow().wrappedPrivateKeys()));
        }
    }

    @Test
    void theNewIdentityIsAProperlyBoundOne() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(stack.store());
            identities.replace(IdentityKeyGenerator.generate(stack.kek()));
            new IdentityReset(identities, stack.kek()).reset(resetConfirmation());

            final IdentityKeyRecord now = identities.load().orElseThrow();
            assertTrue(IdentityKeyGenerator.verifyBinding(now.ed25519Public(),
                    now.x25519Public(), now.bindingSignature()),
                    "a reset that produced an unbound identity would fail closed at the "
                            + "first handshake, which is a worse way to find out");
        }
    }

    @Test
    void thereIsExactlyOneStoredIdentityAfterwards() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(stack.store());
            identities.replace(IdentityKeyGenerator.generate(stack.kek()));
            new IdentityReset(identities, stack.kek()).reset(resetConfirmation());
            assertTrue(1 == stack.store().count(RecordKind.IDENTITY_KEY_RECORD),
                    "the row is replaced, not appended to; a second identity row would "
                            + "leave the old wrapped envelope openable");
        }
    }

    @Test
    void contentSurvivesAResetBecauseAResetIsNotAWipe() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(stack.store());
            identities.replace(IdentityKeyGenerator.generate(stack.kek()));
            stack.store().put(RecordKind.LOCAL_PROFILE, "self",
                    LocalStore.payloadOf("my profile"), null);
            final byte[] blocked = LocalStore.payloadOf("peer");
            stack.store().put(RecordKind.BLOCK_ENTRY, "b1", blocked, blocked);

            new IdentityReset(identities, stack.kek()).reset(resetConfirmation());

            assertTrue(stack.store().get(RecordKind.LOCAL_PROFILE, "self").isPresent(),
                    "the user asked to stop being this person, not to lose their profile");
            assertTrue(1 == stack.store().count(RecordKind.BLOCK_ENTRY),
                    "and resetting an identity must never unblock anybody");
            assertFalse(stack.kek().isDestroyed(),
                    "the KEK stays, because the content the user is keeping needs it");
        }
    }

    @Test
    void aResetWithoutAConfirmationIsRefused() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(stack.store());
            identities.replace(IdentityKeyGenerator.generate(stack.kek()));
            final byte[] before = identities.load().orElseThrow().ed25519Public();
            final IdentityReset reset = new IdentityReset(identities, stack.kek());

            assertThrows(IllegalArgumentException.class, () -> reset.reset(null));
            assertArrayEquals(before, identities.load().orElseThrow().ed25519Public());
        }
    }

    @Test
    void aWipeConfirmationCannotBeSpentOnAReset() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final DeliberateConfirmation.Confirmation forWipe = DeliberateConfirmation
                    .issue(DeliberateConfirmation.Action.EMERGENCY_WIPE, "erase everything", NOW)
                    .confirm("erase everything", NOW + 1);
            final IdentityReset reset =
                    new IdentityReset(new IdentityRepository(stack.store()), stack.kek());
            assertThrows(IllegalArgumentException.class, () -> reset.reset(forWipe));
        }
    }

    @Test
    void theWarningNamesMatchesConversationsAndWhatSurvives() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityRepository identities = new IdentityRepository(stack.store());
            identities.replace(IdentityKeyGenerator.generate(stack.kek()));

            final IdentityReset.Consequences consequences =
                    new IdentityReset(identities, stack.kek()).consequences();
            final String lost = String.join(" ", consequences.lost()).toLowerCase(Locale.ROOT);
            final String kept = String.join(" ", consequences.kept()).toLowerCase(Locale.ROOT);
            final String residual =
                    String.join(" ", consequences.residual()).toLowerCase(Locale.ROOT);

            // SRS-IDN-003 names both, in those words.
            assertTrue(lost.contains("match"), "matches");
            assertTrue(lost.contains("conversation"), "conversation continuity");
            assertTrue(kept.contains("profile") && kept.contains("block list"),
                    "and what stays, so a reset is not mistaken for a wipe");
            assertTrue(residual.contains("storage"),
                    "the limit is stated rather than glossed - the old keys are "
                            + "overwritten, not shredded, and the copy says so");
        }
    }

    @Test
    void theWarningIsHonestOnADeviceWithNoIdentityYet() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            final IdentityReset.Consequences consequences =
                    new IdentityReset(new IdentityRepository(stack.store()), stack.kek())
                            .consequences();
            final String lost = String.join(" ", consequences.lost()).toLowerCase(Locale.ROOT);
            assertFalse(lost.contains("any match you already have"),
                    "a device with no identity has no matches, and telling someone they "
                            + "are about to lose things they do not have is not a warning");
        }
    }
}

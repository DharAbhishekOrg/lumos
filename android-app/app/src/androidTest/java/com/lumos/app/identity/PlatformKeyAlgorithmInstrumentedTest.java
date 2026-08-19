/*
 * Lumos - android-app/app / What the Android platform JCA actually provides.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-ONB-006, SRS-NFR-SEC-002
 * ADRs: ADR-001, ADR-008
 * Threading: any
 * Notes: THIS TEST EXISTS TO RECORD A GAP, AND IT PASSES WHILE THE GAP IS OPEN.
 *
 * Slice S1 stated that identity generation "works on JDK 17 and on API 33+" and
 * that only physical API 26-32 devices were affected. Slice S4c ran the
 * generation on an emulator for the first time and that is not what happens.
 * Measured on API 34 and, when this test first ran in CI, on API 30:
 *
 *                                            API 30   API 34
 *   KeyPairGenerator.getInstance("XDH")      absent   resolves
 *   Signature.getInstance("Ed25519")         absent   resolves
 *   KeyPairGenerator.getInstance("Ed25519")  absent   ABSENT
 *
 * So S1 was half right, and the half it got right is narrower than it looks.
 * XDH and Ed25519 SIGNING did arrive with API 33, exactly as S1 said. Ed25519
 * key GENERATION never arrived at all. The boundary for the identity is
 * therefore not an API level: it is Android.
 *
 * The API-30 column is why this test is written against Build.VERSION rather
 * than as three flat assertions - the emulator job runs API 30 and a local AVD
 * is API 34, and a test that only held on one of them would be worse than no
 * test.
 *
 * What that means, precisely, so nobody has to re-derive it:
 *
 * - IdentityKeyGenerator.generate() cannot run on any Android runtime today.
 *   The JVM unit suite exercises it because the JDK provider does have it.
 * - Everything that generates an identity is therefore JVM-proven and
 *   device-unproven: onboarding's identity creation (SRS-ONB-006), identity
 *   reset (SRS-IDN-003) and the restore half of recovery (SRS-ONB-007).
 * - Nothing SHIPS in WP-02, so no user is exposed by the interval.
 *
 * The fix is ADR-001's own answer - libsodium, which is already in the tree as
 * Lazysodium for the recovery record - and the owner ruling of 2026-08-14 parks
 * it until WP-03 device work rather than taking it here. This test is the
 * tripwire: it asserts the algorithms are ABSENT, so the day a platform update
 * or a provider change makes them available, it fails and says so. When that
 * happens, delete this class and enable the tests it names.
 */

package com.lumos.app.identity;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import org.junit.Test;
import org.junit.runner.RunWith;

/** A recorded capability probe, not a passing feature. */
@RunWith(AndroidJUnit4.class)
public class PlatformKeyAlgorithmInstrumentedTest {

    /** The API level at which the platform provider gained the EdEC algorithms. */
    private static final int EDEC_API_LEVEL = 33;

    @Test
    public void thePlatformStillCannotGenerateEd25519Keys() {
        try {
            KeyPairGenerator.getInstance("Ed25519");
            fail("Ed25519 key generation is available on this runtime. That is good news: "
                    + "IdentityKeyGenerator now works on a device. Delete this test, enable "
                    + "the identity-dependent instrumented tests it names, and record the "
                    + "minimum API level it appeared at.");
        } catch (NoSuchAlgorithmException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void x25519GenerationTracksTheApiLevelExactlyAsSliceS1Said() throws Exception {
        // The half of S1's claim that held up. Recorded rather than assumed,
        // because it is the difference between "the identity needs one new
        // primitive" and "it needs two", and that changes what a libsodium
        // provider has to cover.
        if (Build.VERSION.SDK_INT >= EDEC_API_LEVEL) {
            assertNotNull(KeyPairGenerator.getInstance("XDH"));
        } else {
            try {
                KeyPairGenerator.getInstance("XDH");
                fail("XDH resolved below API " + EDEC_API_LEVEL + ", earlier than S1 found");
            } catch (NoSuchAlgorithmException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void ed25519SignatureSupportTracksTheApiLevelWhileGenerationNeverArrives()
            throws Exception {
        // The detail that made S1's claim look right: from API 33 Android DOES
        // have Ed25519 signing and verification, so verifyBinding() works on a
        // current device. What it has never had is a way to MAKE the key pair,
        // which is what generate() needs - and below API 33 it has neither.
        if (Build.VERSION.SDK_INT >= EDEC_API_LEVEL) {
            assertNotNull(Signature.getInstance("Ed25519"));
        } else {
            try {
                Signature.getInstance("Ed25519");
                fail("Ed25519 signing resolved below API " + EDEC_API_LEVEL
                        + ", earlier than S1 found");
            } catch (NoSuchAlgorithmException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}

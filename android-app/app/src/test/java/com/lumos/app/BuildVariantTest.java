/*
 * Lumos - android-app/app / Variant-inspection tests for the four build environments.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-REP-004
 * ADRs: -
 * Threading: any
 * Notes: TRS-REP-004's verification method is "variant inspection", and this is
 * that inspection expressed as a test. It runs ONCE PER VARIANT: Gradle compiles
 * a separate BuildConfig for each flavour, so testDevDebugUnitTest and
 * testProdDebugUnitTest are different programs reading different constants. Four
 * green runs are the evidence; one is not, which is why the CI job names all
 * four rather than relying on a single `test` task.
 *
 * The expected table below is TRANSCRIBED BY HAND from TRS-REP-004 and the
 * flavour block of build.gradle.kts. That is deliberate and is the same
 * discipline tools/constantsgen uses for the register: a test that read the
 * expected values out of BuildConfig - or out of the Gradle model - would agree
 * with the build script by construction and could never catch a flavour whose
 * environment string was changed by accident. The WP-01 closeout records the
 * cost of that mistake in Sec. 5.2.
 *
 * The flavour is named "qa" rather than "test" because AGP reserves the "test"
 * product-flavour prefix; LUMOS_ENVIRONMENT still carries TRS-REP-004's own
 * vocabulary, and this file is where the two names are reconciled explicitly.
 */

package com.lumos.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Asserts that the build variant under test is the environment it claims to be. */
final class BuildVariantTest {

    /** The base application id, before any per-flavour suffix. */
    private static final String BASE_APPLICATION_ID = "com.lumos.app";

    /** One flavour's expected identity, transcribed from TRS-REP-004. */
    private static final class Expected {
        private final String environment;
        private final String applicationIdSuffix;

        Expected(final String environment, final String applicationIdSuffix) {
            this.environment = environment;
            this.applicationIdSuffix = applicationIdSuffix;
        }
    }

    /**
     * The four TRS-REP-004 environments, keyed by AGP product-flavour name.
     * Insertion order is the requirement's own order: development, test,
     * staging, production.
     */
    private static final Map<String, Expected> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("dev", new Expected("development", ".dev"));
        EXPECTED.put("qa", new Expected("test", ".qa"));
        EXPECTED.put("staging", new Expected("staging", ".staging"));
        EXPECTED.put("prod", new Expected("production", ""));
    }

    /**
     * The variant being tested is one TRS-REP-004 names. A flavour added without
     * a decision about which environment it is fails here rather than shipping
     * as a fifth, undocumented environment.
     */
    @Test
    void flavourIsOneOfTheFourDeclaredEnvironments() {
        assertTrue(
                EXPECTED.containsKey(BuildConfig.FLAVOR),
                "flavour '" + BuildConfig.FLAVOR + "' is not one of the four TRS-REP-004 environments "
                        + EXPECTED.keySet());
    }

    /**
     * This variant's LUMOS_ENVIRONMENT is the one its flavour is supposed to
     * carry. This is the assertion the WP-01 closeout recorded as missing.
     */
    @Test
    void environmentMatchesTheFlavourUnderTest() {
        final Expected expected = EXPECTED.get(BuildConfig.FLAVOR);
        assertEquals(
                expected.environment,
                BuildConfig.LUMOS_ENVIRONMENT,
                "flavour " + BuildConfig.FLAVOR + " must resolve LUMOS_ENVIRONMENT to its declared environment");
    }

    /**
     * Each non-production environment carries its own application id suffix, so
     * two environments can be installed side by side and cannot share app
     * storage, accounts or keystore entries. Production carries no suffix.
     */
    @Test
    void applicationIdSeparatesThisEnvironmentFromTheOthers() {
        final Expected expected = EXPECTED.get(BuildConfig.FLAVOR);
        assertEquals(
                BASE_APPLICATION_ID + expected.applicationIdSuffix,
                BuildConfig.APPLICATION_ID,
                "each environment needs a distinct application id so their on-device state cannot merge");
    }

    /**
     * The table itself must describe four separable environments: four distinct
     * names and four distinct application ids. Without this, a copy-paste in the
     * flavour block could give two environments the same identity and the
     * per-variant assertions above would still pass in every variant.
     */
    @Test
    void theFourEnvironmentsAreDistinctFromEachOther() {
        final Set<String> environments = new HashSet<>();
        final Set<String> applicationIds = new HashSet<>();
        for (final Expected e : EXPECTED.values()) {
            environments.add(e.environment);
            applicationIds.add(BASE_APPLICATION_ID + e.applicationIdSuffix);
        }
        assertEquals(4, environments.size(), "the four environment names must be distinct");
        assertEquals(4, applicationIds.size(), "the four application ids must be distinct");
    }
}

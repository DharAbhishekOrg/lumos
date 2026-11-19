/*
 * Lumos - android-app/app / The feature-permission table, held to its copy.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-004, SRS-ONB-005, SRS-ONB-003, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Three properties, each executed: the table maps every feature to the
 * right permission set per OS version (and the photo library to NONE - the
 * picker is the grant); every feature's rationale and denied-capability copy
 * keys actually exist in strings.xml, so SRS-ONB-005's "explain the exact
 * unavailable capability" cannot rot into a missing-resource crash; and the
 * class exposes no request-everything entry point - the structural half of
 * "only when the related feature is first invoked".
 */

package com.lumos.app.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import android.Manifest;
import android.annotation.SuppressLint;
import com.lumos.app.ble.BlePermissions;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** FeaturePermissions, held to the matrix and the copy. */
@SuppressLint("InlinedApi")
final class FeaturePermissionsTest {

    private static Map<String, String> strings;

    @BeforeAll
    static void loadStrings() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Document document = factory.newDocumentBuilder().parse(
                java.nio.file.Paths.get(System.getProperty("lumos.repoRoot"))
                        .resolve("android-app/app/src/main/res/values/strings.xml").toFile());
        strings = new HashMap<>();
        final NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element element = (Element) nodes.item(i);
            strings.put(element.getAttribute("name"), element.getTextContent());
        }
    }

    @Test
    void nearbyDelegatesToTheBleMatrix() {
        for (final int sdk : new int[] {26, 30, 31, 36}) {
            assertEquals(BlePermissions.requiredRuntimePermissions(sdk),
                    FeaturePermissions.requiredRuntimePermissions(
                            FeaturePermissions.Feature.NEARBY_DISCOVERY, sdk),
                    "one BLE matrix, consumed, never copied");
        }
    }

    @Test
    void theTableIsExactlyTheDeclaredNeeds() {
        assertEquals(List.of(Manifest.permission.CAMERA),
                FeaturePermissions.requiredRuntimePermissions(
                        FeaturePermissions.Feature.PROFILE_PHOTO_CAPTURE, 36));
        assertEquals(List.of(Manifest.permission.RECORD_AUDIO),
                FeaturePermissions.requiredRuntimePermissions(
                        FeaturePermissions.Feature.VOICE_NOTES, 36));
        assertEquals(List.of(Manifest.permission.POST_NOTIFICATIONS),
                FeaturePermissions.requiredRuntimePermissions(
                        FeaturePermissions.Feature.NOTIFICATIONS, 36));
        assertEquals(List.of(),
                FeaturePermissions.requiredRuntimePermissions(
                        FeaturePermissions.Feature.NOTIFICATIONS, 30),
                "pre-33 Android has no notification grant to ask for");
        assertEquals(List.of(),
                FeaturePermissions.requiredRuntimePermissions(
                        FeaturePermissions.Feature.PROFILE_PHOTO_LIBRARY, 36),
                "the photo picker IS the grant (SRS-ONB-004's ideal)");
    }

    @Test
    void everyFeatureHasItsRationaleAndItsDeniedCapabilityCopy() {
        for (final FeaturePermissions.Feature feature : FeaturePermissions.Feature.values()) {
            final String rationale = strings.get(FeaturePermissions.rationaleCopyKey(feature));
            final String denied = strings.get(FeaturePermissions.deniedCapabilityCopyKey(feature));
            assertTrue(rationale != null && !rationale.isBlank(),
                    feature + " needs its plain-language rationale (SRS-ONB-003)");
            assertTrue(denied != null && !denied.isBlank(),
                    feature + " needs its exact-capability denial copy (SRS-ONB-005)");
        }
    }

    @Test
    void deniedCopyNamesWhatContinuesNotJustWhatStops() {
        // SRS-ONB-005: a reduced feature set, explained. The denial copy for
        // every permission-bearing feature says what still works.
        for (final FeaturePermissions.Feature feature : FeaturePermissions.Feature.values()) {
            if (FeaturePermissions.requiredRuntimePermissions(feature, 36).isEmpty()) {
                continue;
            }
            final String denied = strings.get(FeaturePermissions.deniedCapabilityCopyKey(feature))
                    .toLowerCase(java.util.Locale.ROOT);
            assertTrue(denied.contains("still work") || denied.contains("you can still"),
                    feature + "'s denial copy must say what continues, or a denial reads "
                            + "as the app breaking");
        }
    }

    @Test
    void thereIsNoRequestEverythingEntryPoint() {
        // The structural half of SRS-ONB-004: every public method takes a
        // single Feature, so a launch-time request-all loop has nothing to
        // call.
        final List<String> methods = new ArrayList<>();
        for (final Method method : FeaturePermissions.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            methods.add(method.getName());
            assertFalse(method.getParameterCount() == 0,
                    method.getName() + " takes no feature, which is how a "
                            + "request-at-launch path would start");
        }
        assertTrue(methods.contains("requiredRuntimePermissions"));
    }
}

/*
 * Lumos - android-app/app / The Nearby copy, held to SRS-NBY-008 and -016.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-008, SRS-NBY-016, SRS-NBY-014
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: The vocabulary rule as an executable test, extending the clean scan
 * the WP-03 closeout recorded: every nearby_* string a user can read is
 * scanned for plumbing vocabulary (identifiers, protocol nouns, signal
 * units) and for distance claims (SRS-NBY-016 - indoor radio cannot
 * honestly give metres, so no string may). The ban list is matched
 * case-insensitively on word boundaries so "nearby" never trips "by" and a
 * future "Bluetooth" sentence stays legal - Bluetooth is the user-facing
 * radio name, not plumbing. The empty-state wording is pinned to promise
 * authentication ("sure they are real"), because that sentence is the UI
 * face of TRS-ENV-002's zero-false-positive rule.
 */

package com.lumos.app.nearby;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Scans the nearby_* strings for forbidden vocabulary. */
final class NearbyCopyTest {

    private static final String[] PLUMBING = {
        "MAC", "UUID", "MTU", "mesh", "GATT", "RSSI", "dBm", "EID", "packet",
        "advertise", "advertising", "advertisement", "broadcast", "scan response",
        "duty cycle", "fragment", "handshake", "hex", "identifier",
    };

    private static final String[] DISTANCE = {
        "meter", "meters", "metre", "metres", "feet", "foot", "yards", "km",
    };

    private static Map<String, String> strings;

    @BeforeAll
    static void loadStrings() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        // The property the build sets for every test task; the onboarding
        // package keeps its own package-private copy of this lookup.
        final String root = System.getProperty("lumos.repoRoot");
        assertTrue(root != null && !root.isBlank(), "lumos.repoRoot must be set by the build");
        final Document document = factory.newDocumentBuilder().parse(
                Paths.get(root).resolve("android-app/app/src/main/res/values/strings.xml")
                        .toFile());
        strings = new HashMap<>();
        final NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element element = (Element) nodes.item(i);
            final String name = element.getAttribute("name");
            if (name.startsWith("nearby_")) {
                strings.put(name,
                        element.getTextContent().replaceAll("\\s+", " ").trim());
            }
        }
    }

    private static boolean containsWord(final String text, final String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    @Test
    void theNearbyStringsExistForEveryState() {
        for (final String name : new String[] {"nearby_state_radio_off",
            "nearby_state_permission_needed", "nearby_state_radio_unavailable",
            "nearby_state_radio_busy", "nearby_state_empty", "nearby_card_title",
            "nearby_card_hide", "nearby_stop_everything"}) {
            assertTrue(strings.containsKey(name), name + " must exist");
        }
    }

    @Test
    void noPlumbingVocabularyReachesAUser() {
        for (final Map.Entry<String, String> entry : strings.entrySet()) {
            for (final String banned : PLUMBING) {
                assertFalse(containsWord(entry.getValue(), banned),
                        entry.getKey() + " says '" + banned + "' - plumbing vocabulary "
                                + "never reaches a user (SRS-NBY-008)");
            }
        }
    }

    @Test
    void noStringClaimsADistance() {
        for (final Map.Entry<String, String> entry : strings.entrySet()) {
            for (final String banned : DISTANCE) {
                assertFalse(containsWord(entry.getValue(), banned),
                        entry.getKey() + " says '" + banned + "' - indoor radio cannot "
                                + "honestly give a distance (SRS-NBY-016)");
            }
        }
    }

    @Test
    void theEmptyStatePromisesAuthenticationNotSpeed() {
        assertTrue(strings.get("nearby_state_empty").contains("sure they are real"),
                "the empty state is where the zero-false-positive promise is made "
                        + "in user words (TRS-ENV-002)");
    }
}

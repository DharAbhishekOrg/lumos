/*
 * Lumos - android-app/app / The user copy, checked as an artefact.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-001, SRS-ONB-002, SRS-ONB-008, SRS-ONB-007, SRS-IDN-003, SRS-SAF-009
 * ADRs: -
 * Threading: any
 * Notes: Copy usually goes unchecked until somebody reads it on a screen, which
 * on this project is a work package away. These are the checks worth having
 * before then, and they are not spell-checking:
 *
 * - The two confirmation phrases are load-bearing. DeliberateConfirmation
 *   compares what the user typed against them, so a phrase that drifts out of
 *   sync with the code makes an irreversible action unconfirmable - or worse,
 *   confirmable by something else.
 * - SRS-SAF-009 requires the wipe copy to "describe irreversible consequences",
 *   which is a testable claim about words.
 * - The plain-language rules at the top of strings.xml are enforceable, and a
 *   rule nothing enforces is a rule the next person will not follow.
 *
 * The jargon list is short and specific rather than a general readability
 * score. A test that failed on a Flesch-Kincaid threshold would be argued with;
 * a test that says "you wrote 'cryptographic'" is actionable.
 */

package com.lumos.app.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.safety.DeliberateConfirmation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The first user-facing copy, held to the rules written beside it. */
final class OnboardingCopyTest {

    /** Longest average sentence, in words, before copy stops being plain. */
    private static final int MAX_AVERAGE_SENTENCE_WORDS = 25;

    private static Map<String, String> strings;

    @BeforeAll
    static void loadStrings() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Document document = factory.newDocumentBuilder().parse(
                RepoFiles.repoRoot().resolve("android-app/app/src/main/res/values/strings.xml")
                        .toFile());

        strings = new HashMap<>();
        final NodeList nodes = document.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element element = (Element) nodes.item(i);
            strings.put(element.getAttribute("name"), unescape(element.getTextContent()));
        }
    }

    /**
     * Undoes Android string escaping and collapses the source line wrapping.
     *
     * @param raw the element text as the XML holds it
     * @return the words a user would see
     */
    private static String unescape(final String raw) {
        return raw.replace("\\'", "'").replaceAll("\\s+", " ").trim();
    }

    @Test
    void theConfirmationPhrasesAreTheOnesTheCodeCompares() throws Exception {
        for (final Map.Entry<DeliberateConfirmation.Action, String> pair : Map.of(
                DeliberateConfirmation.Action.EMERGENCY_WIPE, "wipe_confirm_phrase",
                DeliberateConfirmation.Action.IDENTITY_RESET, "reset_confirm_phrase").entrySet()) {
            final String phrase = strings.get(pair.getValue());
            assertTrue(phrase != null && !phrase.isBlank(), pair.getValue() + " is missing");

            // The prompt must actually contain the phrase it is asking for.
            final String promptKey = pair.getValue().replace("_phrase", "_prompt");
            assertTrue(strings.get(promptKey).toLowerCase(Locale.ROOT)
                            .contains(phrase.toLowerCase(Locale.ROOT)),
                    promptKey + " must show the exact words " + promptKey.replace("_prompt", "")
                            + " requires");

            // And the challenge must accept it, which is the whole point.
            final DeliberateConfirmation.Challenge challenge =
                    DeliberateConfirmation.issue(pair.getKey(), phrase, 0L);
            challenge.confirm(phrase, 1L);
        }
    }

    @Test
    void theWipeCopyDescribesIrreversibleConsequences() {
        // SRS-SAF-009: "shall describe irreversible consequences".
        final String all = (strings.get("wipe_body") + " " + strings.get("wipe_irreversible")
                + " " + strings.get("wipe_order_note") + " " + strings.get("wipe_residue_note"))
                .toLowerCase(Locale.ROOT);
        assertTrue(all.contains("cannot be undone"), "the wipe copy must say so plainly");
        assertTrue(all.contains("erases everything") || all.contains("erase everything"),
                "and say what goes");
        assertTrue(all.contains("nothing to restore"),
                "and that there is no recovery unless the user already made one");
        assertTrue(all.contains("traces"),
                "SRS-SAF-010's documented platform limit belongs in the copy, not only "
                        + "in WipeCoverage - threat model Sec. 7.3");
    }

    @Test
    void theResetCopySaysWhatIsLostAndWhatIsKept() {
        // SRS-IDN-003: "clear warning that existing matches and encrypted
        // conversation continuity may be lost".
        final String warning = strings.get("reset_warning").toLowerCase(Locale.ROOT);
        assertTrue(warning.contains("matched"), "matches");
        assertTrue(warning.contains("conversation"), "conversation continuity");
        assertTrue(warning.contains("cannot be undone"));
        final String kept = strings.get("reset_body").toLowerCase(Locale.ROOT);
        assertTrue(kept.contains("stay"),
                "a warning that only lists losses drives people to wipe instead");
    }

    @Test
    void theVisibilityCopyNamesAllFourTriggers() {
        // SRS-ONB-008 names installation, update, reboot and permission grant.
        final String copy = strings.get("visibility_never_automatic").toLowerCase(Locale.ROOT);
        assertTrue(copy.contains("install"), "installation");
        assertTrue(copy.contains("update"), "update");
        assertTrue(copy.contains("restart"), "reboot");
        assertTrue(copy.contains("bluetooth"), "permission grant");
    }

    @Test
    void theAgeGateCopySaysTheDateIsNotKept() {
        final String copy = strings.get("age_gate_body").toLowerCase(Locale.ROOT);
        assertTrue(copy.contains("do not keep it"),
                "the privacy property is only real to a user if the screen says it");
    }

    @Test
    void theRecoveryCopySaysTheKeysAreNotInTheFileAndTheDestinationIsTheirs() {
        // SRS-ONB-007 and threat model LT-25.
        assertTrue(strings.get("recovery_setup_what_is_not_in_it").toLowerCase(Locale.ROOT)
                .contains("keys are not in the file"));
        assertTrue(strings.get("recovery_setup_destination_warning").toLowerCase(Locale.ROOT)
                .contains("you decide where"));
        assertTrue(strings.get("recovery_restore_new_identity_warning").toLowerCase(Locale.ROOT)
                .contains("new person"));
    }

    @Test
    void theOnboardingCopySaysThereIsNoAccount() {
        final String copy = strings.get("identity_no_account_note").toLowerCase(Locale.ROOT);
        assertTrue(copy.contains("no sign-up"));
        assertTrue(copy.contains("phone number"));
        assertTrue(copy.contains("email"));
    }

    @Test
    void noJargonReachesTheUser() {
        final List<String> banned = List.of(
                "cryptographic", "key material", "aead", "ciphertext", "plaintext",
                "provisioning", "credential", "entropy", "kdf", "argon2", "keystore",
                "utilise", "leverage", "onboarding");
        final List<String> offences = new ArrayList<>();
        for (final Map.Entry<String, String> entry : strings.entrySet()) {
            final String text = entry.getValue().toLowerCase(Locale.ROOT);
            for (final String word : banned) {
                if (text.contains(word)) {
                    offences.add(entry.getKey() + " contains '" + word + "'");
                }
            }
        }
        assertTrue(offences.isEmpty(), "a person deciding whether to erase their phone "
                + "should not have to learn a vocabulary first: " + offences);
    }

    @Test
    void theCopyAddressesThePersonReadingIt() {
        final List<String> offences = new ArrayList<>();
        for (final Map.Entry<String, String> entry : strings.entrySet()) {
            if (entry.getValue().toLowerCase(Locale.ROOT).contains("the user")) {
                offences.add(entry.getKey());
            }
        }
        assertTrue(offences.isEmpty(), "second person, not third: " + offences);
    }

    @Test
    void sentencesStayShortEnoughToRead() {
        final List<String> offences = new ArrayList<>();
        for (final Map.Entry<String, String> entry : strings.entrySet()) {
            final String[] sentences = entry.getValue().split("[.!?]");
            int words = 0;
            int counted = 0;
            for (final String sentence : sentences) {
                final String trimmed = sentence.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                words += trimmed.split("\\s+").length;
                counted++;
            }
            if (counted > 0 && words / counted > MAX_AVERAGE_SENTENCE_WORDS) {
                offences.add(entry.getKey() + " averages " + words / counted + " words");
            }
        }
        assertTrue(offences.isEmpty(), "these need breaking up: " + offences);
    }

    @Test
    void everyKeyTheFlowsNeedIsPresent() {
        final List<String> required = List.of(
                "age_gate_title", "age_gate_body", "age_gate_blocked_title",
                "age_gate_blocked_body", "consent_title", "consent_accept",
                "consent_doc_terms", "consent_doc_privacy", "consent_doc_community",
                "consent_doc_radio", "consent_updated_body", "identity_no_account_note",
                "identity_offline_note", "visibility_off_body", "visibility_never_automatic",
                "recovery_setup_what_is_not_in_it", "recovery_setup_passphrase_warning",
                "recovery_setup_destination_warning", "recovery_restore_failed",
                "recovery_restore_partial_notice", "reset_warning", "reset_confirm_phrase",
                "wipe_irreversible", "wipe_order_note", "wipe_residue_note",
                "wipe_confirm_phrase");
        for (final String key : required) {
            assertTrue(strings.containsKey(key), "missing copy: " + key);
            assertFalse(strings.get(key).isBlank(), "blank copy: " + key);
        }
    }
}

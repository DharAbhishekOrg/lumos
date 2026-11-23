/*
 * Lumos - android-app/app / Tests: the conversation copy keeps the house rules.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-002, SRS-UX-014, TRS-UX-005
 * ADRs: -
 * Threading: test-only
 * Notes: TRS-UX-005's copy audit is a gate, not a suggestion (EDP WP-10):
 * no word of the machinery reaches a person. Also checked: values go in by
 * numbered argument (a translation can reorder them), sentences stay short,
 * and the failure messages promise what the code does - the text is kept.
 */

package com.lumos.app.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class ChatCopyTest {

    private static final int MAX_AVERAGE_SENTENCE_WORDS = 14;
    private static final Pattern JARGON = Pattern.compile(
            "\\b(relay|ratchet|envelope|prekey|bluetooth|ble|gatt|mtu|eid|server|packet|payload|sync|encrypt\\w*|"
                    + "cipher\\w*|key|keys|session|protocol|outbox|queue\\w*)\\b");
    private static final Map<String, String> STRINGS = new LinkedHashMap<>();

    @BeforeAll
    static void load() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("android-app"))) {
            dir = dir.getParent();
        }
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final NodeList nodes = f.newDocumentBuilder().parse(dir.resolve(
                "android-app/app/src/main/res/values/strings_chat.xml").toFile()).getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element e = (Element) nodes.item(i);
            STRINGS.put(e.getAttribute("name"), e.getTextContent().replace("\\'", "'").replaceAll("\\s+", " ").trim());
        }
        assertTrue(STRINGS.size() > 20, "the chat copy was found and read");
    }

    @Test
    void noWordOfTheMachineryReachesAPerson() {
        for (final Map.Entry<String, String> e : STRINGS.entrySet()) {
            final Matcher m = JARGON.matcher(e.getValue().toLowerCase(Locale.ROOT));
            assertFalse(m.find(), e.getKey() + " says \"" + (m.find(0) ? m.group() : "") + "\"");
        }
    }

    @Test
    void valuesGoInByNumberedArgumentSoTranslationsCanReorderThem() {
        for (final Map.Entry<String, String> e : STRINGS.entrySet()) {
            assertFalse(e.getValue().matches(".*%[sd].*"), e.getKey() + " uses a bare %s or %d");
        }
    }

    @Test
    void sentencesAreShort() {
        int sentences = 0;
        int words = 0;
        for (final String s : STRINGS.values()) {
            for (final String sentence : s.split("[.?!]")) {
                if (!sentence.isBlank()) {
                    sentences++;
                    words += sentence.trim().split("\\s+").length;
                }
            }
        }
        assertTrue(words / sentences <= MAX_AVERAGE_SENTENCE_WORDS, "average sentence is " + words / sentences + " words");
    }

    @Test
    void aSendThatDidNotGoSaysTheWordsAreKept() {
        for (final String key : new String[] {"composer_not_ready", "composer_failed"}) {
            assertTrue(STRINGS.get(key).contains("kept"), key + " must tell the person their message is kept");
        }
    }
}

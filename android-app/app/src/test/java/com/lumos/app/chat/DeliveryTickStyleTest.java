/*
 * Lumos - android-app/app / The ticks look and sound the way the owner ruled.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-013, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Walks every delivery state. The pins: one grey tick for sent, two
 * grey for delivered, two blue for read, red for failed, a clock for queued
 * and sending, the nearby mark for nearby-only content; every state has its
 * own spoken label, so delivered and read differ in words and not only in
 * colour; and no label uses plumbing vocabulary (relay, server, upload,
 * ACK), which would tell a user how the message travelled instead of where
 * it stands.
 */

package com.lumos.app.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.R;
import com.lumos.protocol.chat.DeliveryMachine;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** DeliveryTickStyle over every state. */
final class DeliveryTickStyleTest {

    private static DeliveryTickStyle.Style style(final DeliveryMachine.State s) {
        return DeliveryTickStyle.of(s);
    }

    @Test
    void theTicksFollowTheOwnersWhatsAppStyleRuling() {
        assertEquals(R.drawable.ic_tick_clock, style(DeliveryMachine.State.QUEUED).drawableRes());
        assertEquals(R.drawable.ic_tick_clock, style(DeliveryMachine.State.SENDING).drawableRes());
        assertEquals(R.drawable.ic_tick_single, style(DeliveryMachine.State.SENT).drawableRes());
        assertEquals(R.drawable.ic_tick_double, style(DeliveryMachine.State.DELIVERED).drawableRes());
        assertEquals(R.drawable.ic_tick_double, style(DeliveryMachine.State.READ).drawableRes());
        assertEquals(R.drawable.ic_tick_failed, style(DeliveryMachine.State.FAILED).drawableRes());
        assertEquals(R.drawable.ic_tick_nearby,
                style(DeliveryMachine.State.LOCAL_ONLY_WAITING).drawableRes());

        assertEquals(R.color.tick_sent, style(DeliveryMachine.State.SENT).colorRes());
        assertEquals(R.color.tick_sent, style(DeliveryMachine.State.DELIVERED).colorRes(),
                "delivered is two GREY ticks");
        assertEquals(R.color.tick_read, style(DeliveryMachine.State.READ).colorRes(),
                "read is two BLUE ticks");
        assertEquals(R.color.tick_failed, style(DeliveryMachine.State.FAILED).colorRes());
    }

    @Test
    void everyStateHasItsOwnSpokenLabel() {
        final Set<Integer> labels = new HashSet<>();
        for (final DeliveryMachine.State s : DeliveryMachine.State.values()) {
            assertTrue(labels.add(style(s).labelRes()),
                    s + " shares a spoken label with another state");
        }
        assertNotEquals(style(DeliveryMachine.State.DELIVERED).labelRes(),
                style(DeliveryMachine.State.READ).labelRes(),
                "delivered and read must differ in words, not only in colour");
    }

    @Test
    void noLabelTellsTheUserHowTheMessageTravelled() throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final String root = System.getProperty("lumos.repoRoot");
        assertTrue(root != null && !root.isBlank(), "lumos.repoRoot must be set by the build");
        final Document doc = factory.newDocumentBuilder().parse(Paths.get(root)
                .resolve("android-app/app/src/main/res/values/strings.xml").toFile());
        final Map<String, String> delivery = new HashMap<>();
        final NodeList nodes = doc.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            final Element e = (Element) nodes.item(i);
            if (e.getAttribute("name").startsWith("delivery_")) {
                delivery.put(e.getAttribute("name"), e.getTextContent().trim());
            }
        }
        assertEquals(DeliveryMachine.State.values().length, delivery.size(),
                "one spoken label per delivery state");
        for (final Map.Entry<String, String> entry : delivery.entrySet()) {
            assertFalse(entry.getValue().isBlank(), entry.getKey() + " is empty");
            for (final String banned : new String[] {"relay", "server", "upload", "ack",
                "packet", "envelope", "bluetooth"}) {
                assertFalse(Pattern.compile("\\b" + banned + "\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(entry.getValue()).find(),
                        entry.getKey() + " says '" + banned + "': labels say where a message "
                                + "stands, not how it travelled");
            }
        }
    }
}

/*
 * Lumos - android-app/app / The person's read-receipt choice persists, and fails toward privacy.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-014, TRS-LOC-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Over the real store: no row is the product default; the choice
 * survives a reopen of the settings object; a row this build cannot read is
 * off; and the payload carries nothing but the choice and its time.
 */

package com.lumos.app.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.store.RecordKind;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** ChatSettings over the real store. */
final class ChatSettingsTest {

    private static final long NOW = 1_755_561_600_000L;

    @Test
    void noStoredChoiceIsTheProductDefault() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            assertEquals(ChatSettings.READ_RECEIPTS_DEFAULT, new ChatSettings(stack.store()).readReceipts());
        }
    }

    @Test
    void theChoicePersistsBothWays() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            new ChatSettings(stack.store()).setReadReceipts(false, NOW);
            assertFalse(new ChatSettings(stack.store()).readReceipts(), "off survives a fresh reader");
            new ChatSettings(stack.store()).setReadReceipts(true, NOW + 1);
            assertTrue(new ChatSettings(stack.store()).readReceipts(), "and so does on");
        }
    }

    @Test
    void anUnreadableRowIsOffNeverBackOn() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            for (final String payload : new String[] {"lumos-chat-settings/2\nread_receipts=on\n",
                "garbage", "lumos-chat-settings/1\n", "lumos-chat-settings/1\nread_receipts=yes\n"}) {
                stack.store().put(RecordKind.CHAT_SETTINGS, ChatSettings.SETTINGS_ID,
                        payload.getBytes(StandardCharsets.UTF_8), null);
                assertFalse(new ChatSettings(stack.store()).readReceipts(), payload);
            }
        }
    }

    @Test
    void thePayloadIsTheChoiceAndItsTimeOnly() throws Exception {
        try (Fixtures.Stack stack = Fixtures.openInMemory()) {
            new ChatSettings(stack.store()).setReadReceipts(false, NOW);
            final String payload = new String(stack.store().get(RecordKind.CHAT_SETTINGS, ChatSettings.SETTINGS_ID)
                    .orElseThrow(), StandardCharsets.UTF_8);
            assertEquals("lumos-chat-settings/1\nread_receipts=off\n" + NOW + "\n", payload);
        }
    }
}

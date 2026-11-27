/*
 * Lumos - android-app/app / The person's chat settings: whether read receipts are shared.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-014, TRS-LOC-001
 * ADRs: ADR-003
 * Threading: serial-executor (the store's)
 * Notes: One sealed chat_settings row (schema version 8). Read receipts can
 * be turned off without touching delivery receipts, which never depend on
 * this (SRS-RMT-014; ChatStore.markRead takes the answer). With no row, the
 * product default applies (READ_RECEIPTS_DEFAULT, on, as before this class;
 * the owner may change it). A row this build cannot read - a newer format, a
 * damaged payload - reads as OFF: a person who chose to stop sharing is never
 * turned back on by a format change. The row holds the choice and when it
 * was made, nothing about anyone else.
 */

package com.lumos.app.chat;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The chat settings the person controls. */
public final class ChatSettings {

    /** Read receipts with no stored choice. */
    public static final boolean READ_RECEIPTS_DEFAULT = true;

    /** The single-row identifier. */
    static final String SETTINGS_ID = "self";

    /** Payload format version. */
    private static final String VERSION_LINE = "lumos-chat-settings/1";

    private static final String READ_RECEIPTS_ON = "read_receipts=on";
    private static final String READ_RECEIPTS_OFF = "read_receipts=off";

    private final LocalStore store;

    /**
     * @param store the opened local store
     */
    public ChatSettings(final LocalStore store) {
        this.store = store;
    }

    /**
     * Whether read receipts go to the people this person talks to.
     *
     * @return the stored choice; the default with none; off when unreadable
     * @throws StoreException on engine failure
     */
    public boolean readReceipts() throws StoreException {
        final Optional<byte[]> raw = store.get(RecordKind.CHAT_SETTINGS, SETTINGS_ID);
        if (raw.isEmpty()) {
            return READ_RECEIPTS_DEFAULT;
        }
        final String[] lines = new String(raw.get(), StandardCharsets.UTF_8).split("\n", -1);
        return lines.length >= 2 && VERSION_LINE.equals(lines[0]) && READ_RECEIPTS_ON.equals(lines[1]);
    }

    /**
     * Records the person's read-receipt choice.
     *
     * @param enabled true to share read receipts
     * @param atUtcMillis when the person chose it
     * @throws StoreException on low storage or engine failure
     */
    public void setReadReceipts(final boolean enabled, final long atUtcMillis) throws StoreException {
        final String payload = VERSION_LINE + "\n" + (enabled ? READ_RECEIPTS_ON : READ_RECEIPTS_OFF) + "\n"
                + atUtcMillis + "\n";
        store.put(RecordKind.CHAT_SETTINGS, SETTINGS_ID, payload.getBytes(StandardCharsets.UTF_8), null);
    }
}

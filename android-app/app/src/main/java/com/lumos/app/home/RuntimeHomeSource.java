/*
 * Lumos - android-app/app / The home shell's lists, from the running chat stack.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-UX-012, SRS-RMT-014
 * ADRs: ADR-012
 * Threading: serial-executor
 * Notes: Runs on the app's relay executor, where the chat store lives. A store
 * that cannot open, or an install that has not onboarded, reads as no
 * conversations: the lists show their empty state, never an error dialog.
 * The read-receipt choice is ChatSettings, read and written here too.
 */

package com.lumos.app.home;

import com.lumos.app.LumosApplication;
import com.lumos.app.chat.ConversationList;
import com.lumos.app.relay.ChatRuntime;
import com.lumos.app.store.StoreException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;

/** The chat stack behind the Matches and Chats tabs. */
public final class RuntimeHomeSource implements HomeSource {

    private final LumosApplication app;

    /** @param app the running app */
    public RuntimeHomeSource(final LumosApplication app) {
        this.app = app;
    }

    @Override
    public List<ConversationList.Summary> conversations() {
        try {
            final ChatRuntime rt = app.runtime();
            return rt == null ? List.of() : rt.conversations().all();
        } catch (final IOException | GeneralSecurityException | StoreException e) {
            return List.of();
        }
    }

    @Override
    public Optional<Boolean> readReceipts() {
        try {
            final ChatRuntime rt = app.runtime();
            return rt == null ? Optional.empty() : Optional.of(rt.settings().readReceipts());
        } catch (final IOException | GeneralSecurityException | StoreException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean setReadReceipts(final boolean enabled) {
        try {
            final ChatRuntime rt = app.runtime();
            if (rt == null) {
                return false;
            }
            rt.settings().setReadReceipts(enabled, System.currentTimeMillis());
            return true;
        } catch (final IOException | GeneralSecurityException | StoreException e) {
            return false;
        }
    }
}

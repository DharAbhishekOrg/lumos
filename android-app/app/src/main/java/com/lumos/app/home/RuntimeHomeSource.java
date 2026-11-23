/*
 * Lumos - android-app/app / The home shell's lists, from the running chat stack.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-UX-012
 * ADRs: ADR-012
 * Threading: serial-executor
 * Notes: Runs on the app's relay executor, where the chat store lives. A store
 * that cannot open, or an install that has not onboarded, reads as no
 * conversations: the lists show their empty state, never an error dialog.
 */

package com.lumos.app.home;

import com.lumos.app.LumosApplication;
import com.lumos.app.chat.ConversationList;
import com.lumos.app.relay.ChatRuntime;
import com.lumos.app.store.StoreException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

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
}

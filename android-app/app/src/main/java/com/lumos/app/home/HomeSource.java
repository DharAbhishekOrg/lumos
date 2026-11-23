/*
 * Lumos - android-app/app / What the home shell's lists need from the running app.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-UX-008
 * ADRs: ADR-012
 * Threading: serial-executor
 * Notes: The seam between the Matches and Chats tabs and the chat stack, as
 * ConversationSource is for one conversation: the activity never touches the
 * store, so the instrumented test drives it with a script. Called on the
 * app's relay executor only. RuntimeHomeSource is the real one.
 */

package com.lumos.app.home;

import com.lumos.app.chat.ConversationList;
import java.util.List;

/** The conversations, as the home shell sees them. */
public interface HomeSource {

    /**
     * Every conversation, newest activity first. Empty when the chat stack is
     * not running or cannot be read; never throws at the screen.
     *
     * @return the summaries
     */
    List<ConversationList.Summary> conversations();
}

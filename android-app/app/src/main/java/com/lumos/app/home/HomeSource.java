/*
 * Lumos - android-app/app / What the home shell's lists need from the running app.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-UX-008
 * ADRs: ADR-012
 * Threading: serial-executor
 * Notes: The seam between the Matches and Chats tabs and the chat stack, as
 * ConversationSource is for one conversation: the activity never touches the
 * store, so the instrumented test drives it with a script. Called on the
 * app's relay executor only. RuntimeHomeSource is the real one. It also
 * carries the person's read-receipt choice for the switch on the Chats tab
 * (SRS-RMT-014); a source with no chat stack has no choice to show.
 */

package com.lumos.app.home;

import com.lumos.app.chat.ConversationList;
import java.util.List;
import java.util.Optional;

/** The conversations, as the home shell sees them. */
public interface HomeSource {

    /**
     * Every conversation, newest activity first. Empty when the chat stack is
     * not running or cannot be read; never throws at the screen.
     *
     * @return the summaries
     */
    List<ConversationList.Summary> conversations();

    /**
     * The person's read-receipt choice.
     *
     * @return the choice, or empty when the chat stack is not running or the
     *     choice cannot be read (the switch is then not shown)
     */
    default Optional<Boolean> readReceipts() {
        return Optional.empty();
    }

    /**
     * Records the person's read-receipt choice.
     *
     * @param enabled true to share read receipts
     * @return true once stored; false when it could not be
     */
    default boolean setReadReceipts(final boolean enabled) {
        return false;
    }
}

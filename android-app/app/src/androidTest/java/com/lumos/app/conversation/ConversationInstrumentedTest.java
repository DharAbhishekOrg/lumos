/*
 * Lumos - android-app/app / The conversation screen, on a device, against a scripted chat stack.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-010, SRS-SAF-001, SRS-UX-011, SRS-UX-012, SRS-NFR-A11Y-001, SRS-NFR-A11Y-007,
 *             SRS-NFR-SEC-013, TRS-UX-004
 * ADRs: -
 * Threading: test-only
 * Notes: The real activity, layouts and adapter, with the chat stack
 * replaced by a script, so the test reads what a person and a screen reader
 * would get: the name and reach, the offline banner instead of a spinner,
 * each bubble as one spoken sentence that includes the delivery state in
 * words, its actions offered to accessibility services, a send button that
 * only works with something to send, a send that reaches the stack and
 * clears the composer, and the safety sheet: named for the person, saying
 * they are not told, acting only after the confirmation, then closing; and
 * leaving the screen blanks its app-switcher thumbnail (ADR-011).
 */

package com.lumos.app.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lumos.app.R;
import com.lumos.app.chat.ChatRecords;
import com.lumos.app.relay.ConversationSafety;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Drives ConversationActivity with a scripted source. */
@RunWith(AndroidJUnit4.class)
public final class ConversationInstrumentedTest {

    private static final byte[] MATCH = new byte[16];

    /** A scripted chat stack. */
    private static final class Scripted implements ConversationSource {
        final List<RemoteText.TextEvent> sent = new CopyOnWriteArrayList<>();
        final List<ChatRecords.Message> messages = new CopyOnWriteArrayList<>();
        final List<ConversationSafety.Ending> ended = new CopyOnWriteArrayList<>();

        @Override
        public boolean entryAllowed(final byte[] matchId) {
            return true;
        }

        @Override
        public PeerSummary peer(final byte[] matchId) {
            return new PeerSummary("Aanya", PeerSummary.Reach.REMOTE);
        }

        @Override
        public List<ChatRecords.Message> messages(final byte[] matchId) {
            return new ArrayList<>(messages);
        }

        @Override
        public boolean online() {
            return false;
        }

        @Override
        public SendOutcome send(final byte[] matchId, final RemoteText.TextEvent event) {
            sent.add(event);
            return SendOutcome.QUEUED;
        }

        @Override
        public boolean deleteForMe(final byte[] messageId) {
            return true;
        }

        @Override
        public void markRead(final byte[] matchId) {
            // Nothing to record.
        }

        @Override
        public boolean end(final byte[] matchId, final ConversationSafety.Ending how) {
            ended.add(how);
            return true;
        }
    }

    private static ChatRecords.Message message(final boolean mine, final String text, final long at,
            final DeliveryMachine.State state) {
        return new ChatRecords.Message(ChatRecords.newMessageId(), MATCH, mine, text.getBytes(StandardCharsets.UTF_8),
                at, at, state, false, false, new byte[0], new byte[0]);
    }

    private static void waitFor(final BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(100);
        }
        assertTrue("condition not met in time", condition.getAsBoolean());
    }

    @After
    public void clear() {
        ConversationActivity.setSource(null);
    }

    @Test
    public void thePersonAndAScreenReaderGetTheWholeConversation() throws Exception {
        final Scripted s = new Scripted();
        final long now = System.currentTimeMillis();
        s.messages.add(message(false, "Was that you laughing at the drummer?", now - 60_000, DeliveryMachine.State.DELIVERED));
        s.messages.add(message(true, "Guilty.", now, DeliveryMachine.State.READ));
        ConversationActivity.setSource(s);
        try (ActivityScenario<ConversationActivity> sc = ActivityScenario.launch(ConversationActivity.intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), MATCH))) {
            final List<RecyclerView> list = new ArrayList<>();
            waitFor(() -> {
                final boolean[] ready = new boolean[1];
                sc.onActivity(a -> {
                    final RecyclerView rv = a.findViewById(R.id.conversation_messages);
                    ready[0] = rv.getAdapter() != null && rv.getAdapter().getItemCount() == 3 && rv.getChildCount() == 3;
                    list.clear();
                    list.add(rv);
                });
                return ready[0];
            });
            sc.onActivity(a -> {
                assertEquals("Aanya", ((TextView) a.findViewById(R.id.conversation_name)).getText().toString());
                assertEquals(a.getString(R.string.conversation_reach_remote),
                        ((TextView) a.findViewById(R.id.conversation_reach)).getText().toString());
                assertEquals("offline is an inline banner", View.VISIBLE,
                        a.findViewById(R.id.conversation_offline).getVisibility());
                final RecyclerView rv = list.get(0);
                assertEquals(a.getString(R.string.conversation_day_today), ((TextView) rv.getChildAt(0)).getText());
                final String theirs = rv.getChildAt(1).getContentDescription().toString();
                assertTrue(theirs, theirs.startsWith("Aanya, ") && theirs.contains("drummer"));
                final String mine = rv.getChildAt(2).getContentDescription().toString();
                assertTrue(mine, mine.startsWith("You, ") && mine.endsWith(a.getString(R.string.delivery_read)));
                final AccessibilityNodeInfo node = rv.getChildAt(2).createAccessibilityNodeInfo();
                final List<CharSequence> labels = new ArrayList<>();
                for (final AccessibilityNodeInfo.AccessibilityAction act : node.getActionList()) {
                    if (act.getLabel() != null) {
                        labels.add(act.getLabel().toString());
                    }
                }
                assertTrue(labels.toString(), labels.contains(a.getString(R.string.action_react))
                        && labels.contains(a.getString(R.string.action_delete_for_everyone)));
            });
        }
    }

    @Test
    @SuppressLint("SetTextI18n") // the test types a message, as a person would; it is not UI copy
    public void sendWorksOnlyWithSomethingToSendAndClearsTheComposer() throws Exception {
        final Scripted s = new Scripted();
        ConversationActivity.setSource(s);
        try (ActivityScenario<ConversationActivity> sc = ActivityScenario.launch(ConversationActivity.intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), MATCH))) {
            sc.onActivity(a -> {
                assertFalse("nothing to send yet", a.findViewById(R.id.composer_send).isEnabled());
                assertEquals(View.VISIBLE, a.findViewById(R.id.conversation_empty).getVisibility());
                ((EditText) a.findViewById(R.id.composer_text)).setText("  Window seat?  ");
                assertTrue(a.findViewById(R.id.composer_send).isEnabled());
                a.findViewById(R.id.composer_send).performClick();
            });
            waitFor(() -> !s.sent.isEmpty());
            assertEquals(TextEventKind.TEXT, s.sent.get(0).kind());
            assertEquals("Window seat?", new String(s.sent.get(0).body(), StandardCharsets.UTF_8));
            waitFor(() -> {
                final boolean[] cleared = new boolean[1];
                sc.onActivity(a -> cleared[0] = ((EditText) a.findViewById(R.id.composer_text)).getText().length() == 0);
                return cleared[0];
            });
        }
    }

    @Test
    public void theSafetySheetNamesThePersonActsOnlyWhenConfirmedAndThenCloses() throws Exception {
        final Scripted s = new Scripted();
        ConversationActivity.setSource(s);
        try (ActivityScenario<ConversationActivity> sc = ActivityScenario.launch(ConversationActivity.intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), MATCH))) {
            waitFor(() -> {
                final boolean[] named = new boolean[1];
                sc.onActivity(a -> named[0] = "Aanya".contentEquals(
                        ((TextView) a.findViewById(R.id.conversation_name)).getText()));
                return named[0];
            });
            sc.onActivity(a -> {
                final View shield = a.findViewById(R.id.conversation_safety);
                assertEquals(a.getString(R.string.safety_open, "Aanya"), shield.getContentDescription().toString());
                assertTrue("a 48dp target", shield.getHeight() >= Math.round(48 * a.getResources().getDisplayMetrics().density));
                shield.performClick();
                final SafetySheet sheet = a.safetySheet();
                assertTrue(sheet.sheet().isShowing());
                assertEquals("Safety with Aanya",
                        ((TextView) sheet.sheet().findViewById(R.id.safety_title)).getText().toString());
                final String note = ((TextView) sheet.sheet().findViewById(R.id.safety_note)).getText().toString();
                assertTrue(note, note.contains("not told"));
                sheet.sheet().findViewById(R.id.safety_unmatch).performClick();
                assertFalse("the sheet gives way to the confirmation", sheet.sheet().isShowing());
                sheet.confirmation().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("cancel does nothing", s.ended.isEmpty());

            sc.onActivity(a -> {
                a.findViewById(R.id.conversation_safety).performClick();
                final SafetySheet sheet = a.safetySheet();
                sheet.sheet().findViewById(R.id.safety_block).performClick();
                sheet.confirmation().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            });
            waitFor(() -> !s.ended.isEmpty());
            assertEquals(List.of(ConversationSafety.Ending.BLOCK), s.ended);
            assertTrue("nothing was sent to them", s.sent.isEmpty());
            waitFor(() -> sc.getState() == Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void leavingTheConversationBlanksItsAppSwitcherThumbnail() {
        // ADR-011 ruling 1 below API 33 (the CI emulator is API 30): FLAG_SECURE only while leaving.
        Assume.assumeTrue("API 33+ uses setRecentsScreenshotEnabled, which has no getter",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU);
        ConversationActivity.setSource(new Scripted());
        try (ActivityScenario<ConversationActivity> sc = ActivityScenario.launch(ConversationActivity.intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), MATCH))) {
            sc.onActivity(a -> assertEquals("screenshots in the chat are never blocked (evidence)", 0,
                    a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
            sc.moveToState(Lifecycle.State.STARTED);
            sc.onActivity(a -> assertTrue("the thumbnail taken on the way out is blank",
                    (a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0));
        }
    }
}

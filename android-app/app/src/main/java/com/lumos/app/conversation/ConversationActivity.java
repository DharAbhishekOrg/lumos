/*
 * Lumos - android-app/app / One conversation on screen.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-005, SRS-RMT-010, SRS-SAF-001, SRS-UX-011, SRS-UX-012, TRS-AND-003, TRS-AND-010,
 *             TRS-UX-011
 * ADRs: ADR-011, ADR-012
 * Threading: main
 * Notes: Deliberately thin, like NearbyActivity: it runs the presenter on the
 * app's relay executor (the chat store's one thread), posts the result back
 * to the main thread and draws it. It refreshes while visible, so a message
 * the relay pass brings in appears without a pull, and marks the
 * conversation read when it shows. A draft survives rotation and process
 * death through the saved instance state (TRS-UX-011). A send that did not
 * go keeps the text and says why in a snackbar; nothing blocks the screen
 * (SRS-UX-012). The shield in the header opens the safety sheet; a
 * confirmed unmatch or block runs on the worker, and on success the screen
 * says so and closes, because the conversation no longer exists. Like
 * every screen, it is kept out of the app switcher's thumbnails (ADR-011).
 */

package com.lumos.app.conversation;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.lumos.app.LumosApplication;
import com.lumos.app.R;
import com.lumos.app.onboarding.AppSurface;
import com.lumos.app.relay.ConversationSafety;
import com.lumos.app.safety.RecentsRedaction;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/** Shows one conversation and its composer. */
public final class ConversationActivity extends AppCompatActivity implements MessageAdapter.Listener {

    /** The intent extra holding the match id. */
    public static final String EXTRA_MATCH_ID = "com.lumos.app.conversation.MATCH_ID";

    /** How often a visible conversation re-reads the store for arrivals. */
    static final Duration REFRESH = Duration.ofSeconds(4);

    private static final String STATE_DRAFT = "draft";
    private static volatile ConversationSource sourceOverride;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshLoop;
    private ConversationPresenter presenter;
    private Executor worker;
    private MessageAdapter adapter;
    private RecyclerView list;
    private EditText composer;
    private ImageButton send;
    private String shownName = "";
    private SafetySheet safetySheet;
    private ConversationRows.Bubble replyingTo;
    private boolean visible;

    /**
     * The intent that opens a conversation.
     *
     * @param context any context
     * @param matchId the conversation
     * @return the intent
     */
    public static Intent intent(final Context context, final byte[] matchId) {
        return new Intent(context, ConversationActivity.class).putExtra(EXTRA_MATCH_ID, matchId.clone());
    }

    /**
     * Replaces the chat stack with a scripted one, for the instrumented demo.
     *
     * @param source the source, or null for the real one
     */
    public static void setSource(final ConversationSource source) {
        sourceOverride = source;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RecentsRedaction.created(this, AppSurface.CONVERSATIONS);
        final byte[] matchId = getIntent().getByteArrayExtra(EXTRA_MATCH_ID);
        if (matchId == null || !(getApplication() instanceof LumosApplication)) {
            finish();
            return;
        }
        final LumosApplication app = (LumosApplication) getApplication();
        final ConversationSource source = sourceOverride != null ? sourceOverride : new RuntimeConversationSource(app);
        presenter = new ConversationPresenter(source, matchId);
        worker = app.relayExecutor();
        setContentView(R.layout.activity_conversation);
        applyInsets();

        adapter = new MessageAdapter(this);
        list = findViewById(R.id.conversation_messages);
        final LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        list.setAdapter(adapter);

        composer = findViewById(R.id.composer_text);
        send = findViewById(R.id.composer_send);
        if (savedInstanceState != null) {
            composer.setText(savedInstanceState.getString(STATE_DRAFT, ""));
        }
        composer.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int a, final int b, final int c) {
                // Nothing to do before.
            }

            @Override
            public void onTextChanged(final CharSequence s, final int a, final int b, final int c) {
                updateSendButton();
            }

            @Override
            public void afterTextChanged(final Editable s) {
                // Nothing to do after.
            }
        });
        updateSendButton();
        send.setOnClickListener(v -> sendDraft());
        findViewById(R.id.conversation_back).setOnClickListener(v -> finish());
        findViewById(R.id.conversation_reply_cancel).setOnClickListener(v -> stopReply());
        SwipeToReply.attach(list, this::bubbleAt, this::startReply);
        findViewById(R.id.conversation_safety).setOnClickListener(
                v -> safetySheet = SafetySheet.show(this, shownName, this::endConversation));
        ViewCompat.setAccessibilityHeading(findViewById(R.id.conversation_name), true);
    }

    private void applyInsets() {
        final View root = findViewById(R.id.conversation_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void updateSendButton() {
        final boolean ok = ConversationPresenter.canSend(composer.getText().toString());
        send.setEnabled(ok);
        send.setAlpha(ok ? 1f : 0.4f);
    }

    @Override
    protected void onSaveInstanceState(final Bundle out) {
        super.onSaveInstanceState(out);
        if (composer != null) {
            out.putString(STATE_DRAFT, composer.getText().toString());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecentsRedaction.leaving(this, AppSurface.CONVERSATIONS, false);
        visible = true;
        reload(true);
        main.postDelayed(refresh, REFRESH.toMillis());
    }

    @Override
    protected void onPause() {
        RecentsRedaction.leaving(this, AppSurface.CONVERSATIONS, true);
        super.onPause();
        visible = false;
        main.removeCallbacks(refresh);
    }

    private void refreshLoop() {
        if (visible) {
            reload(true);
            main.postDelayed(refresh, REFRESH.toMillis());
        }
    }

    /** Reads on the worker, draws on the main thread. */
    private void reload(final boolean markRead) {
        final Locale locale = getResources().getConfiguration().getLocales().get(0);
        worker.execute(() -> {
            final ConversationPresenter.Screen screen = presenter.load(System.currentTimeMillis(), ZoneId.systemDefault(),
                    locale);
            if (markRead && screen.open()) {
                presenter.markRead();
            }
            main.post(() -> draw(screen));
        });
    }

    private void draw(final ConversationPresenter.Screen screen) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!screen.open()) {
            finish();
            return;
        }
        final String name = screen.peer().displayName().isEmpty()
                ? getString(R.string.conversation_fallback_name) : screen.peer().displayName();
        shownName = name;
        ((TextView) findViewById(R.id.conversation_name)).setText(name);
        findViewById(R.id.conversation_safety).setContentDescription(getString(R.string.safety_open, name));
        ((TextView) findViewById(R.id.conversation_avatar)).setText(name.substring(0, name.offsetByCodePoints(0, 1)));
        ((TextView) findViewById(R.id.conversation_reach)).setText(reachText(screen.peer().reach()));
        findViewById(R.id.conversation_offline).setVisibility(screen.online() ? View.GONE : View.VISIBLE);
        findViewById(R.id.conversation_empty).setVisibility(screen.rows().isEmpty() ? View.VISIBLE : View.GONE);
        adapter.setPeerName(screen.peer().displayName());
        final boolean atBottom = !list.canScrollVertically(1);
        final int before = adapter.getItemCount();
        adapter.submitList(new ArrayList<>(screen.rows()), () -> {
            if (atBottom || before == 0) {
                list.scrollToPosition(Math.max(0, adapter.getItemCount() - 1));
            }
        });
    }

    private int reachText(final PeerSummary.Reach reach) {
        switch (reach) {
            case HERE_NOW:
                return R.string.conversation_reach_here_now;
            case REMOTE:
                return R.string.conversation_reach_remote;
            default:
                return R.string.conversation_reach_waits;
        }
    }

    private void sendDraft() {
        final String draft = composer.getText().toString();
        send.setEnabled(false);
        worker.execute(() -> {
            final ConversationRows.Bubble answered = replyingTo;
            final ConversationPresenter.SendResult r = answered == null ? presenter.sendText(draft)
                    : presenter.sendReply(draft, answered);
            main.post(() -> {
                if (r == ConversationPresenter.SendResult.SENT) {
                    composer.setText("");
                    stopReply();
                    reload(false);
                } else {
                    explain(r);
                }
                updateSendButton();
            });
        });
    }

    private void explain(final ConversationPresenter.SendResult r) {
        final int msg;
        switch (r) {
            case TOO_LONG:
                msg = R.string.composer_too_long;
                break;
            case NOT_READY:
                msg = R.string.composer_not_ready;
                break;
            case EMPTY:
                return;
            default:
                msg = R.string.composer_failed;
                break;
        }
        Snackbar.make(list, msg, Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onAction(final ConversationRows.Bubble bubble, final ConversationPresenter.Action action) {
        if (action != null) {
            perform(bubble, action);
            return;
        }
        final List<ConversationPresenter.Action> actions = new ArrayList<>(ConversationPresenter.actionsFor(bubble));
        if (actions.isEmpty()) {
            return;
        }
        final CharSequence[] labels = new CharSequence[actions.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = getString(MessageAdapter.labelOf(actions.get(i)));
        }
        new MaterialAlertDialogBuilder(this)
                .setItems(labels, (d, which) -> perform(bubble, actions.get(which)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Starts answering a message: the bar above the composer names it, and the keyboard opens. */
    void startReply(final ConversationRows.Bubble bubble) {
        replyingTo = bubble;
        final ConversationRows.Quote asQuote = new ConversationRows.Quote(ConversationRows.QuoteKind.TEXT,
                bubble.messageId(), bubble.outgoing(), bubble.text());
        final TextView text = findViewById(R.id.conversation_reply_text);
        text.setText(getString(R.string.reply_quote_line,
                getString(R.string.reply_banner, adapter.authorOf(this, asQuote)), bubble.text()));
        findViewById(R.id.conversation_reply_bar).setVisibility(View.VISIBLE);
        composer.requestFocus();
        final android.view.inputmethod.InputMethodManager ime = getSystemService(
                android.view.inputmethod.InputMethodManager.class);
        if (ime != null) {
            ime.showSoftInput(composer, 0);
        }
    }

    private void stopReply() {
        replyingTo = null;
        findViewById(R.id.conversation_reply_bar).setVisibility(View.GONE);
    }

    private ConversationRows.Bubble bubbleAt(final int position) {
        if (position < 0 || position >= adapter.getItemCount()) {
            return null;
        }
        return adapter.getCurrentList().get(position) instanceof ConversationRows.Bubble b ? b : null;
    }

    /** @return the message being answered, or null; for the instrumented test */
    ConversationRows.Bubble replyingTo() {
        return replyingTo;
    }

    private void perform(final ConversationRows.Bubble bubble, final ConversationPresenter.Action action) {
        switch (action) {
            case REPLY:
                startReply(bubble);
                break;
            case REACT:
                final String[] reactions = ConversationPresenter.REACTIONS.toArray(new String[0]);
                new MaterialAlertDialogBuilder(this).setTitle(R.string.action_react)
                        .setItems(reactions, (d, which) -> background(() -> presenter.react(bubble, reactions[which])))
                        .setNegativeButton(R.string.action_cancel, null).show();
                break;
            case COPY:
                final ClipboardManager cb = getSystemService(ClipboardManager.class);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText(getString(R.string.action_copy), bubble.text()));
                    Snackbar.make(list, R.string.action_copied, Snackbar.LENGTH_SHORT).show();
                }
                break;
            case DELETE_FOR_ME:
                worker.execute(() -> {
                    final boolean gone = presenter.deleteForMe(bubble);
                    main.post(() -> {
                        if (!gone) {
                            Snackbar.make(list, R.string.action_delete_for_me_waiting, Snackbar.LENGTH_LONG).show();
                        }
                        reload(false);
                    });
                });
                break;
            default:
                new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_for_everyone_title)
                        .setMessage(R.string.delete_for_everyone_body)
                        .setPositiveButton(R.string.action_delete_for_everyone,
                                (d, w) -> background(() -> presenter.deleteForEveryone(bubble)))
                        .setNegativeButton(R.string.action_cancel, null).show();
                break;
        }
    }

    /** @return the last safety sheet opened, for the instrumented test */
    SafetySheet safetySheet() {
        return safetySheet;
    }

    /** Carries out a confirmed unmatch or block, then closes the screen. */
    private void endConversation(final ConversationSafety.Ending how) {
        final String name = shownName;
        worker.execute(() -> {
            final boolean ended = presenter.end(how);
            main.post(() -> {
                if (!ended) {
                    Snackbar.make(list, R.string.safety_failed, Snackbar.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(getApplicationContext(), getString(how == ConversationSafety.Ending.BLOCK
                        ? R.string.safety_blocked : R.string.safety_unmatched, name), Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    /** Runs a send-like action, then explains a failure and redraws. */
    private void background(final java.util.function.Supplier<ConversationPresenter.SendResult> action) {
        worker.execute(() -> {
            final ConversationPresenter.SendResult r = action.get();
            main.post(() -> {
                if (r != ConversationPresenter.SendResult.SENT) {
                    explain(r);
                }
                reload(false);
            });
        });
    }
}

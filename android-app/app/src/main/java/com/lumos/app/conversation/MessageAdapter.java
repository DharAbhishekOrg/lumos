/*
 * Lumos - android-app/app / Draws a conversation's rows.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-011, SRS-RMT-005, SRS-RMT-010, TRS-UX-004, SRS-NFR-A11Y-001, SRS-NFR-A11Y-005, SRS-NFR-A11Y-007
 * ADRs: -
 * Threading: main
 * Notes: Rendering only; ConversationRows decided everything. Each bubble is
 * one accessibility element whose description is a full sentence - who,
 * when, what, and for your own messages the delivery state in words - so
 * TalkBack never reads a tick as "image" (SRS-NFR-A11Y-007), and its
 * long-press actions are also offered as accessibility actions, so a person
 * who cannot long-press can still react, copy or delete. The tick glyph and
 * colour come from DeliveryTickStyle, the one table both apps share.
 */

package com.lumos.app.conversation;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.Drawable;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.lumos.app.R;
import com.lumos.app.chat.DeliveryTickStyle;
import java.util.Objects;

/** The conversation list's adapter. */
final class MessageAdapter extends ListAdapter<ConversationRows.Row, RecyclerView.ViewHolder> {

    /** What a bubble asks the screen to do. */
    interface Listener {
        /**
         * @param bubble the bubble long-pressed
         * @param action the action chosen through accessibility, or null to show the menu
         */
        void onAction(ConversationRows.Bubble bubble, ConversationPresenter.Action action);
    }

    private static final int DAY = 0;
    private static final int MINE = 1;
    private static final int THEIRS = 2;

    private final Listener listener;
    private String peerName = "";

    MessageAdapter(final Listener listener) {
        super(new DiffUtil.ItemCallback<>() {
            @Override
            public boolean areItemsTheSame(@NonNull final ConversationRows.Row a, @NonNull final ConversationRows.Row b) {
                return a.key().equals(b.key());
            }

            @Override
            public boolean areContentsTheSame(@NonNull final ConversationRows.Row a,
                    @NonNull final ConversationRows.Row b) {
                return Objects.equals(describe(a), describe(b));
            }
        });
        this.listener = listener;
    }

    /** Everything that changes how a row looks, as one comparable value. */
    private static Object describe(final ConversationRows.Row r) {
        if (r instanceof ConversationRows.Bubble b) {
            final ConversationRows.Quote q = b.quote();
            return java.util.List.of(b.text(), b.deleted(), b.edited(), b.timeLabel(), b.state(), b.myReaction(),
                    b.peerReaction(), b.position(), q == null ? "" : q.kind() + "|" + q.fromMe() + "|" + q.text());
        }
        return r;
    }

    void setPeerName(final String name) {
        peerName = name;
    }

    @Override
    public int getItemViewType(final int position) {
        final ConversationRows.Row r = getItem(position);
        if (r instanceof ConversationRows.Bubble b) {
            return b.outgoing() ? MINE : THEIRS;
        }
        return DAY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int type) {
        final LayoutInflater in = LayoutInflater.from(parent.getContext());
        final int layout = type == DAY ? R.layout.item_day_divider
                : type == MINE ? R.layout.item_message_out : R.layout.item_message_in;
        return new RecyclerView.ViewHolder(in.inflate(layout, parent, false)) { };
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        final ConversationRows.Row r = getItem(position);
        if (r instanceof ConversationRows.DayDivider d) {
            final TextView label = holder.itemView.findViewById(R.id.day_label);
            ViewCompat.setAccessibilityHeading(label, true);
            label.setText(d.day() == ConversationRows.Day.TODAY ? label.getContext().getString(R.string.conversation_day_today)
                    : d.day() == ConversationRows.Day.YESTERDAY
                            ? label.getContext().getString(R.string.conversation_day_yesterday) : d.dateLabel());
            return;
        }
        if (r instanceof ConversationRows.Bubble b) {
            bindBubble(holder.itemView, b);
        }
    }

    private void bindBubble(final View row, final ConversationRows.Bubble b) {
        final TextView text = row.findViewById(R.id.message_text);
        final TextView reactions = row.findViewById(R.id.message_reactions);
        final TextView meta = row.findViewById(R.id.message_meta);
        final boolean tail = b.showsMeta();

        text.setBackgroundResource(b.outgoing() ? (tail ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_out_run)
                : (tail ? R.drawable.bg_bubble_in : R.drawable.bg_bubble_in_run));
        final String shown = b.deleted()
                ? row.getContext().getString(b.outgoing() ? R.string.message_deleted_by_you : R.string.message_deleted_by_them)
                : b.text();
        text.setText(shown);
        text.setAlpha(b.deleted() ? 0.8f : 1f);
        text.setTypeface(null, b.deleted() ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL);

        final String reacts = (b.peerReaction() + " " + b.myReaction()).strip();
        reactions.setVisibility(reacts.isEmpty() ? View.GONE : View.VISIBLE);
        reactions.setText(reacts);

        meta.setVisibility(tail ? View.VISIBLE : View.GONE);
        meta.setText(b.edited() ? row.getContext().getString(R.string.message_time_edited, b.timeLabel())
                : b.timeLabel());
        bindTick(meta, b);

        final String quoted = bindQuote(row, b);
        final String sentence = spoken(row, b, shown, reacts);
        row.setContentDescription(quoted.isEmpty() ? sentence
                : row.getContext().getString(R.string.reply_spoken, quoted, sentence));
        row.setOnLongClickListener(v -> {
            listener.onAction(b, null);
            return true;
        });
        bindAccessibilityActions(row, b);
    }

    /**
     * Shows a reply's quote above its bubble, drawn from this phone's own copy
     * of the answered message (CR-009).
     *
     * @return the quote as shown, empty when there is none
     */
    private String bindQuote(final View row, final ConversationRows.Bubble b) {
        final TextView quote = row.findViewById(R.id.message_quote);
        final ConversationRows.Quote q = b.quote();
        if (q == null) {
            quote.setVisibility(View.GONE);
            return "";
        }
        final Context c = row.getContext();
        final String shown;
        switch (q.kind()) {
            case TEXT:
                shown = c.getString(R.string.reply_quote_line, authorOf(c, q), q.text());
                break;
            case DELETED:
                shown = c.getString(R.string.reply_quote_deleted);
                break;
            default:
                shown = c.getString(R.string.reply_quote_unavailable);
                break;
        }
        quote.setVisibility(View.VISIBLE);
        quote.setText(shown);
        return shown;
    }

    /** Who wrote a quoted message: "You", or the peer's name. */
    String authorOf(final Context c, final ConversationRows.Quote q) {
        if (q.fromMe()) {
            return c.getString(R.string.reply_quote_you);
        }
        return peerName.isEmpty() ? c.getString(R.string.conversation_fallback_name) : peerName;
    }

    /** The tick rides the time as an end drawable: the shared glyph, in its token colour. */
    private static void bindTick(final TextView meta, final ConversationRows.Bubble b) {
        if (!b.outgoing()) {
            meta.setCompoundDrawablesRelative(null, null, null, null);
            return;
        }
        final DeliveryTickStyle.Style style = DeliveryTickStyle.of(b.state());
        final Drawable d = AppCompatResources.getDrawable(meta.getContext(), style.drawableRes());
        if (d == null) {
            return;
        }
        final Drawable tinted = DrawableCompat.wrap(d.mutate());
        DrawableCompat.setTint(tinted, ContextCompat.getColor(meta.getContext(), style.colorRes()));
        final int size = meta.getResources().getDimensionPixelSize(R.dimen.message_tick_size);
        tinted.setBounds(0, 0, size, size);
        meta.setCompoundDrawablesRelative(null, null, tinted, null);
    }

    private String spoken(final View row, final ConversationRows.Bubble b, final String shown, final String reacts) {
        final String sentence = b.outgoing()
                ? row.getContext().getString(R.string.message_spoken_mine, b.timeLabel(), shown,
                        row.getContext().getString(DeliveryTickStyle.of(b.state()).labelRes()))
                : row.getContext().getString(R.string.message_spoken_theirs,
                        peerName.isEmpty() ? row.getContext().getString(R.string.conversation_fallback_name) : peerName,
                        b.timeLabel(), shown);
        return reacts.isEmpty() ? sentence
                : row.getContext().getString(R.string.message_spoken_with_reactions, sentence, reacts);
    }

    /** Replaces a recycled row's actions: the ids live on the view, because rows are reused. */
    private void bindAccessibilityActions(final View row, final ConversationRows.Bubble b) {
        if (row.getTag(R.id.message_row) instanceof java.util.List<?> old) {
            for (final Object id : old) {
                if (id instanceof Integer i) {
                    ViewCompat.removeAccessibilityAction(row, i);
                }
            }
        }
        final java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (final ConversationPresenter.Action a : ConversationPresenter.actionsFor(b)) {
            ids.add(ViewCompat.addAccessibilityAction(row, row.getContext().getString(labelOf(a)), (v, args) -> {
                listener.onAction(b, a);
                return true;
            }));
        }
        row.setTag(R.id.message_row, ids);
    }

    /**
     * The label of an action.
     *
     * @param a the action
     * @return its string resource
     */
    static int labelOf(final ConversationPresenter.Action a) {
        switch (a) {
            case REPLY:
                return R.string.action_reply;
            case REACT:
                return R.string.action_react;
            case COPY:
                return R.string.action_copy;
            case DELETE_FOR_ME:
                return R.string.action_delete_for_me;
            default:
                return R.string.action_delete_for_everyone;
        }
    }
}

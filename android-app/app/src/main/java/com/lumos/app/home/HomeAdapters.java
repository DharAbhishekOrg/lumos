/*
 * Lumos - android-app/app / The Matches and Chats rows, drawn and spoken.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-007, SRS-RMT-005, SRS-NFR-A11Y-001, SRS-UX-014
 * ADRs: -
 * Threading: main
 * Notes: Two small list adapters over HomeLists' rows. Each row is one
 * accessibility node with one spoken sentence (name, what was said, when,
 * how many unread), built from string resources with numbered arguments so a
 * translation can reorder it (SRS-UX-014). A tap opens the conversation.
 */

package com.lumos.app.home;

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.lumos.app.R;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** The home shell's list adapters. */
final class HomeAdapters {

    private HomeAdapters() {
        throw new AssertionError("no instances");
    }

    /** The name a row shows: the peer's, or the fallback until names are stored (CS4). */
    static String nameOf(final Context c, final String name) {
        return name.isEmpty() ? c.getString(R.string.conversation_fallback_name) : name;
    }

    private static String initialOf(final String name) {
        return name.substring(0, name.offsetByCodePoints(0, 1));
    }

    /** One view holder for both row kinds: the row is the only thing it keeps. */
    static final class Holder extends RecyclerView.ViewHolder {
        Holder(final View v) {
            super(v);
        }
    }

    /** The Matches tab. */
    static final class Matches extends ListAdapter<HomeLists.MatchRow, Holder> {

        private final Consumer<byte[]> open;

        Matches(final Consumer<byte[]> open) {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull final HomeLists.MatchRow a, @NonNull final HomeLists.MatchRow b) {
                    return a.key().equals(b.key());
                }

                @Override
                public boolean areContentsTheSame(@NonNull final HomeLists.MatchRow a,
                        @NonNull final HomeLists.MatchRow b) {
                    return a.name().equals(b.name());
                }
            });
            this.open = open;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_match_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, final int position) {
            final HomeLists.MatchRow row = getItem(position);
            final Context c = h.itemView.getContext();
            final String name = nameOf(c, row.name());
            ((TextView) h.itemView.findViewById(R.id.match_name)).setText(name);
            ((TextView) h.itemView.findViewById(R.id.match_avatar)).setText(initialOf(name));
            h.itemView.setContentDescription(c.getString(R.string.matches_spoken, name));
            h.itemView.setOnClickListener(v -> open.accept(row.matchId()));
        }
    }

    /** The Chats tab. */
    static final class Chats extends ListAdapter<HomeLists.ChatRow, Holder> {

        private final Consumer<byte[]> open;

        Chats(final Consumer<byte[]> open) {
            super(new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull final HomeLists.ChatRow a, @NonNull final HomeLists.ChatRow b) {
                    return a.key().equals(b.key());
                }

                @Override
                public boolean areContentsTheSame(@NonNull final HomeLists.ChatRow a,
                        @NonNull final HomeLists.ChatRow b) {
                    return a.name().equals(b.name()) && Objects.equals(a.preview(), b.preview())
                            && a.text().equals(b.text()) && Objects.equals(a.when(), b.when())
                            && a.timeLabel().equals(b.timeLabel()) && a.unread() == b.unread()
                            && Arrays.equals(a.matchId(), b.matchId());
                }
            });
            this.open = open;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, final int position) {
            final HomeLists.ChatRow row = getItem(position);
            final Context c = h.itemView.getContext();
            final Resources r = c.getResources();
            final String name = nameOf(c, row.name());
            final String preview = previewOf(c, row);
            final String time = row.when() == HomeLists.When.YESTERDAY
                    ? c.getString(R.string.chats_yesterday) : row.timeLabel();
            ((TextView) h.itemView.findViewById(R.id.chat_name)).setText(name);
            ((TextView) h.itemView.findViewById(R.id.chat_avatar)).setText(initialOf(name));
            ((TextView) h.itemView.findViewById(R.id.chat_preview)).setText(preview);
            ((TextView) h.itemView.findViewById(R.id.chat_time)).setText(time);
            final TextView badge = h.itemView.findViewById(R.id.chat_unread);
            badge.setVisibility(row.unread() > 0 ? View.VISIBLE : View.GONE);
            badge.setText(row.unreadCapped() ? c.getString(R.string.chats_unread_capped)
                    : NumberFormat.getIntegerInstance(r.getConfiguration().getLocales().get(0)).format(row.unread()));
            String spoken = c.getString(R.string.chats_spoken, name, preview, time);
            if (row.unread() > 0) {
                spoken = c.getString(R.string.chats_spoken_with_unread, spoken,
                        r.getQuantityString(R.plurals.chats_unread_spoken, row.unread(), row.unread()));
            }
            h.itemView.setContentDescription(spoken);
            h.itemView.setOnClickListener(v -> open.accept(row.matchId()));
        }

        private static String previewOf(final Context c, final HomeLists.ChatRow row) {
            switch (Objects.requireNonNull(row.preview())) {
                case DELETED:
                    return c.getString(R.string.chats_preview_deleted);
                case MINE:
                    return c.getString(R.string.chats_preview_mine, row.text());
                default:
                    return row.text();
            }
        }
    }
}

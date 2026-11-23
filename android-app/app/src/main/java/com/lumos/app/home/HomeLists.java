/*
 * Lumos - android-app/app / What the Matches and Chats tabs show, decided off the device.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-UX-002, SRS-UX-012
 * ADRs: -
 * Threading: any
 * Notes: Plain Java, so every rule is JVM-tested. A conversation with nothing
 * said yet is a new match (Matches tab); one with messages is a chat (Chats
 * tab), newest first. A chat row previews its last message as the person
 * would read it: their words, "You: " before your own, or that it was
 * deleted; a reaction-only change does not move a row. The time is the time
 * of day today, "Yesterday", the weekday within the week, then a date, all in
 * the person's zone and locale. Unread counts above 99 read "99+". Names are
 * not stored until profile previews arrive (CS4), so every row says the
 * fallback name; nothing is invented.
 */

package com.lumos.app.home;

import com.lumos.app.chat.ChatRecords;
import com.lumos.app.chat.ConversationList;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the rows of the Matches and Chats tabs. */
public final class HomeLists {

    /** The highest unread count shown as a number; above it the badge reads "99+". */
    public static final int MAX_UNREAD_SHOWN = 99;

    /** Within this many days a chat's time reads as a weekday. */
    static final int WEEKDAY_WINDOW_DAYS = 6;

    private HomeLists() {
        throw new AssertionError("no instances");
    }

    /** How a chat's last message is previewed. */
    public enum Preview {
        /** Their words. */
        THEIRS,
        /** Your words, shown after "You: ". */
        MINE,
        /** The last message was deleted for everyone. */
        DELETED
    }

    /** How a chat's time reads. */
    public enum When {
        /** Today: the time of day in timeLabel. */
        TODAY,
        /** Yesterday, in words. */
        YESTERDAY,
        /** This week: the weekday in timeLabel. */
        WEEKDAY,
        /** Older: the date in timeLabel. */
        DATE
    }

    /**
     * A new match: someone you can say hi to.
     *
     * @param key a stable list key
     * @param matchId the conversation
     * @param name the peer's name, empty until stored (CS4)
     */
    public record MatchRow(String key, byte[] matchId, String name) {

        /**
         * Copies.
         *
         * @param key see the record
         * @param matchId see the record
         * @param name see the record
         */
        public MatchRow {
            matchId = matchId.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }
    }

    /**
     * A chat with something said.
     *
     * @param key a stable list key
     * @param matchId the conversation
     * @param name the peer's name, empty until stored (CS4)
     * @param preview how the last message is previewed
     * @param text the last message's words, empty when deleted
     * @param when how the time reads
     * @param timeLabel the time of day, weekday or date; empty for YESTERDAY
     * @param unread how many of their messages are unread
     */
    public record ChatRow(String key, byte[] matchId, String name, Preview preview, String text, When when,
            String timeLabel, int unread) {

        /**
         * Copies.
         *
         * @param key see the record
         * @param matchId see the record
         * @param name see the record
         * @param preview see the record
         * @param text see the record
         * @param when see the record
         * @param timeLabel see the record
         * @param unread see the record
         */
        public ChatRow {
            matchId = matchId.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }

        /** @return true when the badge reads "99+" rather than the count */
        public boolean unreadCapped() {
            return unread > MAX_UNREAD_SHOWN;
        }
    }

    /**
     * Both tabs' rows.
     *
     * @param matches new matches, nothing said yet
     * @param chats chats, newest first
     */
    public record Lists(List<MatchRow> matches, List<ChatRow> chats) {

        /**
         * Copies.
         *
         * @param matches see the record
         * @param chats see the record
         */
        public Lists {
            matches = List.copyOf(matches);
            chats = List.copyOf(chats);
        }

        /** @return the empty lists */
        public static Lists empty() {
            return new Lists(List.of(), List.of());
        }
    }

    /**
     * Builds both tabs from the conversation summaries.
     *
     * @param summaries every conversation, newest activity first
     * @param nowMillis now, UTC
     * @param zone the person's zone
     * @param locale the person's locale
     * @return the rows
     */
    public static Lists build(final List<ConversationList.Summary> summaries, final long nowMillis,
            final ZoneId zone, final Locale locale) {
        final List<MatchRow> matches = new ArrayList<>();
        final List<ChatRow> chats = new ArrayList<>();
        final LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        for (final ConversationList.Summary s : summaries) {
            final String key = ChatRecords.rowId(s.matchId());
            if (s.last().isEmpty()) {
                matches.add(new MatchRow(key, s.matchId(), ""));
                continue;
            }
            final ChatRecords.Message m = s.last().get();
            final Preview preview = m.deleted() ? Preview.DELETED : m.outgoing() ? Preview.MINE : Preview.THEIRS;
            final String text = m.deleted() ? "" : new String(m.body(), StandardCharsets.UTF_8);
            final LocalDate day = Instant.ofEpochMilli(m.createdAtMillis()).atZone(zone).toLocalDate();
            final When when = when(day, today);
            chats.add(new ChatRow(key, s.matchId(), "", preview, text, when,
                    label(when, m.createdAtMillis(), zone, locale), s.unread()));
        }
        return new Lists(matches, chats);
    }

    static When when(final LocalDate day, final LocalDate today) {
        if (day.equals(today) || day.isAfter(today)) {
            return When.TODAY;
        }
        if (day.equals(today.minusDays(1))) {
            return When.YESTERDAY;
        }
        return day.isAfter(today.minusDays(WEEKDAY_WINDOW_DAYS + 1)) ? When.WEEKDAY : When.DATE;
    }

    private static String label(final When when, final long at, final ZoneId zone, final Locale locale) {
        switch (when) {
            case TODAY:
                return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
                        .format(Instant.ofEpochMilli(at).atZone(zone));
            case YESTERDAY:
                return "";
            case WEEKDAY:
                final DayOfWeek d = Instant.ofEpochMilli(at).atZone(zone).getDayOfWeek();
                return d.getDisplayName(TextStyle.SHORT, locale);
            default:
                return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                        .format(Instant.ofEpochMilli(at).atZone(zone));
        }
    }
}

/*
 * Lumos - android-app/app / A conversation's messages as the rows a person reads.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-010, SRS-RMT-011, SRS-UX-011, TRS-UX-004, SRS-UX-014
 * ADRs: -
 * Threading: any
 * Notes: A pure function from stored messages to rows, so the screen's
 * layout decisions are JVM-tested: a day divider before the first message of
 * each local day (Today, Yesterday, then the date); bubbles grouped into
 * runs from one sender, so only the last of a run carries the tail corner and
 * the time; a deleted message keeps its place and says who deleted it rather
 * than vanishing (the other person may have read it). Times and dates come
 * from java.time in the person's locale and zone. Words come from string
 * resources through the row kinds, never assembled here (SRS-UX-014).
 */

package com.lumos.app.conversation;

import com.lumos.app.chat.ChatRecords;
import com.lumos.protocol.chat.DeliveryMachine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the rows of one conversation. */
public final class ConversationRows {

    /** A message from the same sender within this many minutes continues the run. */
    static final int RUN_GAP_MINUTES = 3;

    private ConversationRows() {
        throw new AssertionError("no instances");
    }

    /** One row of the conversation. */
    public sealed interface Row permits DayDivider, Bubble {
        /** @return a key that is stable across refreshes, for the list's diffing */
        String key();
    }

    /** Which day a divider names. */
    public enum Day {
        /** The person's today. */
        TODAY,
        /** The day before. */
        YESTERDAY,
        /** Any other day: shown as dateLabel. */
        DATE
    }

    /**
     * A day divider.
     *
     * @param key a stable key
     * @param day which kind of day
     * @param dateLabel the localized date, used when day is DATE
     */
    public record DayDivider(String key, Day day, String dateLabel) implements Row { }

    /** Where a bubble sits in a run from one sender. */
    public enum RunPosition {
        /** Alone. */
        SINGLE,
        /** First of several. */
        FIRST,
        /** In the middle. */
        MIDDLE,
        /** Last of several: carries the tail and the time. */
        LAST
    }

    /**
     * One message bubble.
     *
     * @param key the message's row id
     * @param messageId the message id
     * @param outgoing true when this device sent it
     * @param text the text, empty when deleted
     * @param deleted true when deleted for everyone
     * @param edited true when edited after sending
     * @param timeLabel the localized time
     * @param state the delivery state (meaningful for outgoing)
     * @param myReaction this device's reaction, empty for none
     * @param peerReaction the peer's reaction, empty for none
     * @param position where it sits in its run
     */
    public record Bubble(String key, byte[] messageId, boolean outgoing, String text, boolean deleted, boolean edited,
            String timeLabel, DeliveryMachine.State state, String myReaction, String peerReaction,
            RunPosition position) implements Row {

        /**
         * @param key see the record
         * @param messageId see the record
         * @param outgoing see the record
         * @param text see the record
         * @param deleted see the record
         * @param edited see the record
         * @param timeLabel see the record
         * @param state see the record
         * @param myReaction see the record
         * @param peerReaction see the record
         * @param position see the record
         */
        public Bubble {
            messageId = messageId.clone();
        }

        @Override
        public byte[] messageId() {
            return messageId.clone();
        }

        /** @return true when the time and the tick show under this bubble */
        public boolean showsMeta() {
            return position == RunPosition.SINGLE || position == RunPosition.LAST;
        }
    }

    /**
     * Builds the rows.
     *
     * @param messages the conversation, oldest first
     * @param nowMillis the current UTC time
     * @param zone the person's time zone
     * @param locale the person's locale
     * @return the rows, top to bottom
     */
    public static List<Row> build(final List<ChatRecords.Message> messages, final long nowMillis, final ZoneId zone,
            final Locale locale) {
        final DateTimeFormatter time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale);
        final DateTimeFormatter date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        final LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        final List<Row> rows = new ArrayList<>();
        LocalDate shownDay = null;
        for (int i = 0; i < messages.size(); i++) {
            final ChatRecords.Message m = messages.get(i);
            final ZonedDateTime at = Instant.ofEpochMilli(m.createdAtMillis()).atZone(zone);
            if (!at.toLocalDate().equals(shownDay)) {
                shownDay = at.toLocalDate();
                rows.add(divider(shownDay, today, date));
            }
            final String text = m.deleted() ? "" : new String(m.body(), StandardCharsets.UTF_8);
            rows.add(new Bubble(ChatRecords.rowId(m.messageId()), m.messageId(), m.outgoing(), text, m.deleted(),
                    m.edited(), time.format(at), m.state(), utf8(m.myReaction()), utf8(m.peerReaction()),
                    position(messages, i, zone)));
        }
        return rows;
    }

    private static DayDivider divider(final LocalDate day, final LocalDate today, final DateTimeFormatter date) {
        final String key = "day-" + day;
        if (day.equals(today)) {
            return new DayDivider(key, Day.TODAY, "");
        }
        if (day.equals(today.minusDays(1))) {
            return new DayDivider(key, Day.YESTERDAY, "");
        }
        return new DayDivider(key, Day.DATE, date.format(day));
    }

    private static RunPosition position(final List<ChatRecords.Message> ms, final int i, final ZoneId zone) {
        final boolean joinsPrevious = i > 0 && continues(ms.get(i - 1), ms.get(i), zone);
        final boolean joinsNext = i + 1 < ms.size() && continues(ms.get(i), ms.get(i + 1), zone);
        if (joinsPrevious && joinsNext) {
            return RunPosition.MIDDLE;
        }
        if (joinsPrevious) {
            return RunPosition.LAST;
        }
        return joinsNext ? RunPosition.FIRST : RunPosition.SINGLE;
    }

    /** Whether b continues a's run: same sender, same day, within the gap. */
    private static boolean continues(final ChatRecords.Message a, final ChatRecords.Message b, final ZoneId zone) {
        final long gapMillis = Duration.ofMinutes(RUN_GAP_MINUTES).toMillis();
        return a.outgoing() == b.outgoing()
                && b.createdAtMillis() - a.createdAtMillis() <= gapMillis
                && Instant.ofEpochMilli(a.createdAtMillis()).atZone(zone).toLocalDate()
                        .equals(Instant.ofEpochMilli(b.createdAtMillis()).atZone(zone).toLocalDate());
    }

    private static String utf8(final byte[] b) {
        return b.length == 0 ? "" : new String(b, StandardCharsets.UTF_8);
    }
}

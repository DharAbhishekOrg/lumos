/*
 * Lumos - android-app/app / Tests: how stored messages become rows.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-010, SRS-UX-011, TRS-UX-004
 * ADRs: -
 * Threading: test-only
 * Notes: Day dividers follow the person's zone, not UTC; runs break on a new
 * sender, a long pause or midnight; a deleted message keeps its place.
 */

package com.lumos.app.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.chat.ChatRecords;
import com.lumos.protocol.chat.DeliveryMachine;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ConversationRowsTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final byte[] MATCH = new byte[16];
    private static final long NOW = ZonedDateTime.of(2026, 11, 24, 21, 30, 0, 0, KOLKATA).toInstant().toEpochMilli();

    private static long at(final int day, final int hour, final int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, KOLKATA).toInstant().toEpochMilli();
    }

    private static ChatRecords.Message msg(final boolean mine, final String text, final long when, final long seq) {
        return new ChatRecords.Message(ChatRecords.newMessageId(), MATCH, mine, text.getBytes(StandardCharsets.UTF_8),
                when, seq, mine ? DeliveryMachine.State.READ : DeliveryMachine.State.DELIVERED, false, false,
                new byte[0], new byte[0], new byte[0]);
    }

    private static List<ConversationRows.Bubble> bubbles(final List<ConversationRows.Row> rows) {
        final List<ConversationRows.Bubble> out = new ArrayList<>();
        for (final ConversationRows.Row r : rows) {
            if (r instanceof ConversationRows.Bubble b) {
                out.add(b);
            }
        }
        return out;
    }

    @Test
    void daysAreTheLocalDaysWithTodayAndYesterdayInWords() {
        final List<ConversationRows.Row> rows = ConversationRows.build(List.of(
                msg(false, "Sunday", at(22, 18, 0), 1),
                msg(true, "Monday", at(23, 9, 0), 2),
                // 00:10 local on the 24th is still the 23rd in UTC: it must read as Today.
                msg(false, "Just after midnight", at(24, 0, 10), 3)), NOW, KOLKATA, Locale.UK);
        final List<ConversationRows.DayDivider> days = new ArrayList<>();
        for (final ConversationRows.Row r : rows) {
            if (r instanceof ConversationRows.DayDivider d) {
                days.add(d);
            }
        }
        assertEquals(3, days.size());
        assertEquals(ConversationRows.Day.DATE, days.get(0).day());
        assertEquals("22 Nov 2026", days.get(0).dateLabel());
        assertEquals(ConversationRows.Day.YESTERDAY, days.get(1).day());
        assertEquals(ConversationRows.Day.TODAY, days.get(2).day());
        assertInstanceOf(ConversationRows.DayDivider.class, rows.get(0), "the first row names its day");
    }

    @Test
    void runsBreakOnANewSenderALongPauseOrMidnight() {
        final List<ConversationRows.Bubble> b = bubbles(ConversationRows.build(List.of(
                msg(true, "a", at(23, 23, 57), 1),
                msg(true, "b", at(23, 23, 58), 2),
                msg(true, "c", at(24, 0, 0), 3),
                msg(false, "d", at(24, 0, 1), 4),
                msg(false, "e", at(24, 0, 2), 5),
                msg(false, "f", at(24, 0, 9), 6)), NOW, KOLKATA, Locale.UK));
        assertEquals(ConversationRows.RunPosition.FIRST, b.get(0).position());
        assertEquals(ConversationRows.RunPosition.LAST, b.get(1).position(), "midnight ends the run");
        assertEquals(ConversationRows.RunPosition.SINGLE, b.get(2).position());
        assertEquals(ConversationRows.RunPosition.FIRST, b.get(3).position(), "a new sender starts a run");
        assertEquals(ConversationRows.RunPosition.LAST, b.get(4).position());
        assertEquals(ConversationRows.RunPosition.SINGLE, b.get(5).position(), "seven minutes later is a new run");
        assertFalse(b.get(0).showsMeta(), "only the end of a run shows the time and tick");
        assertTrue(b.get(1).showsMeta());
    }

    @Test
    void aDeletedMessageKeepsItsPlaceWithoutItsTextAndReactionsAndTimesCarryThrough() {
        final ChatRecords.Message kept = msg(false, "kept", at(24, 20, 0), 1);
        final ChatRecords.Message gone = msg(true, "secret", at(24, 20, 1), 2).withDeleted();
        final ChatRecords.Message reacted = msg(true, "hello", at(24, 20, 30), 3).withReaction(false,
                "❤️".getBytes(StandardCharsets.UTF_8));
        final List<ConversationRows.Bubble> b = bubbles(ConversationRows.build(List.of(kept, gone, reacted), NOW,
                KOLKATA, Locale.US));
        assertEquals(3, b.size(), "deleting for everyone never removes the row");
        assertTrue(b.get(1).deleted());
        assertEquals("", b.get(1).text(), "the deleted text is not carried to the screen");
        assertEquals("❤️", b.get(2).peerReaction());
        assertEquals("8:30 PM", b.get(2).timeLabel().replace('\u202f', ' '),
                "times are local and in the person's format (JDK 20+ uses a narrow space before PM)");
        assertEquals(DeliveryMachine.State.READ, b.get(2).state());
        assertEquals(ChatRecords.rowId(reacted.messageId()), b.get(2).key(), "keys are the store's row ids");
    }

    @Test
    void anEmptyConversationHasNoRows() {
        assertTrue(ConversationRows.build(List.of(), NOW, KOLKATA, Locale.UK).isEmpty());
    }

    private static ChatRecords.Message reply(final boolean mine, final String text, final byte[] to, final long seq) {
        return new ChatRecords.Message(ChatRecords.newMessageId(), MATCH, mine, text.getBytes(StandardCharsets.UTF_8),
                at(24, 10, (int) seq), seq, DeliveryMachine.State.DELIVERED, false, false, new byte[0], new byte[0], to);
    }

    @Test
    void aQuoteIsDrawnFromThisPhonesOwnCopyOfTheAnsweredMessage() {
        final ChatRecords.Message theirs = msg(false, "Coffee on Saturday?", at(24, 10, 0), 1);
        final ChatRecords.Message mine = msg(true, "Or Sunday?", at(24, 10, 1), 2);
        final ChatRecords.Message gone = msg(true, "", at(24, 10, 2), 3).withDeleted();
        final List<ConversationRows.Bubble> b = bubbles(ConversationRows.build(List.of(theirs, mine, gone,
                reply(true, "Saturday!", theirs.messageId(), 4),
                reply(false, "Sunday is better", mine.messageId(), 5),
                reply(false, "what did you say?", gone.messageId(), 6),
                reply(false, "about that...", ChatRecords.newMessageId(), 7)), NOW, KOLKATA, Locale.UK));

        assertEquals(null, b.get(0).quote(), "a message that answers nothing has no quote");
        final ConversationRows.Quote toTheirs = b.get(3).quote();
        assertEquals(ConversationRows.QuoteKind.TEXT, toTheirs.kind());
        assertEquals("Coffee on Saturday?", toTheirs.text());
        assertFalse(toTheirs.fromMe());
        assertTrue(b.get(4).quote().fromMe(), "the peer answering my message quotes me");
        assertEquals(ConversationRows.QuoteKind.DELETED, b.get(5).quote().kind(),
                "deleted for everyone: the quote says so, and keeps none of the words");
        assertEquals("", b.get(5).quote().text());
        assertEquals(ConversationRows.QuoteKind.UNAVAILABLE, b.get(6).quote().kind(),
                "never had it, deleted it for myself, or it belongs to another chat");
    }

    @Test
    void aDeletedReplyShowsNoQuote() {
        final ChatRecords.Message asked = msg(false, "Hi", at(24, 9, 0), 1);
        final ChatRecords.Message answered = reply(true, "Hey", asked.messageId(), 2).withDeleted();
        assertEquals(null, bubbles(ConversationRows.build(List.of(asked, answered), NOW, KOLKATA, Locale.UK)).get(1)
                .quote());
    }
}

/*
 * Lumos - android-app/app / Tests: what the Matches and Chats tabs show.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-012, SRS-RMT-005, TRS-TST-001
 * ADRs: -
 * Threading: test-only
 * Notes: Built from summaries directly, so each rule is one assertion: new
 * matches go to Matches and chats to Chats in the order given; the preview
 * is their words, your words or "deleted" (never the deleted text); the time
 * reads today, yesterday, weekday or date in the person's zone; and the
 * unread badge caps at 99+.
 */

package com.lumos.app.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.chat.ChatRecords;
import com.lumos.app.chat.ConversationList;
import com.lumos.protocol.chat.DeliveryMachine;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomeListsTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    /** Tuesday 24 November 2026, 14:00 in Kolkata. */
    private static final long NOW = ZonedDateTime.of(2026, 11, 24, 14, 0, 0, 0, KOLKATA).toInstant().toEpochMilli();
    private static final long HOUR = 3_600_000L;

    private static byte[] id(final int n) {
        final byte[] b = new byte[16];
        b[0] = (byte) n;
        return b;
    }

    private static ConversationList.Summary chat(final int n, final boolean mine, final String text, final long at,
            final boolean deleted, final int unread) {
        final ChatRecords.Message m = new ChatRecords.Message(id(100 + n), id(n), mine,
                text.getBytes(StandardCharsets.UTF_8), at, 1, mine ? DeliveryMachine.State.SENT
                : DeliveryMachine.State.DELIVERED, false, deleted, new byte[0], new byte[0], new byte[0]);
        return new ConversationList.Summary(id(n), Optional.of(m), unread);
    }

    private static ConversationList.Summary newMatch(final int n) {
        return new ConversationList.Summary(id(n), Optional.empty(), 0);
    }

    @Test
    void newMatchesAndChatsGoToTheirOwnTabsInTheOrderGiven() {
        final HomeLists.Lists l = HomeLists.build(List.of(chat(1, false, "Jazz at nine?", NOW - HOUR, false, 2),
                chat(2, true, "See you there", NOW - 2 * HOUR, false, 0), newMatch(3)), NOW, KOLKATA, Locale.UK);
        assertEquals(1, l.matches().size());
        assertEquals(id(3)[0], l.matches().get(0).matchId()[0]);
        assertEquals(2, l.chats().size());
        assertEquals(id(1)[0], l.chats().get(0).matchId()[0], "newest first, as the list gave them");
        assertEquals("", l.chats().get(0).name(), "no name is invented before profiles are stored (CS4)");
    }

    @Test
    void thePreviewIsTheirWordsYourWordsOrDeletedNeverTheDeletedText() {
        final HomeLists.Lists l = HomeLists.build(List.of(chat(1, false, "hello", NOW, false, 1),
                chat(2, true, "hi back", NOW, false, 0), chat(3, false, "something I regret", NOW, true, 0)),
                NOW, KOLKATA, Locale.UK);
        assertEquals(HomeLists.Preview.THEIRS, l.chats().get(0).preview());
        assertEquals("hello", l.chats().get(0).text());
        assertEquals(HomeLists.Preview.MINE, l.chats().get(1).preview());
        assertEquals("hi back", l.chats().get(1).text());
        assertEquals(HomeLists.Preview.DELETED, l.chats().get(2).preview());
        assertEquals("", l.chats().get(2).text(), "a deleted message's words never reach the list");
    }

    @Test
    void theTimeReadsTodayYesterdayWeekdayThenDateInThePersonsZone() {
        final LocalDate today = LocalDate.of(2026, 11, 24);
        assertEquals(HomeLists.When.TODAY, HomeLists.when(today, today));
        assertEquals(HomeLists.When.YESTERDAY, HomeLists.when(today.minusDays(1), today));
        assertEquals(HomeLists.When.WEEKDAY, HomeLists.when(today.minusDays(2), today));
        assertEquals(HomeLists.When.WEEKDAY, HomeLists.when(today.minusDays(6), today));
        assertEquals(HomeLists.When.DATE, HomeLists.when(today.minusDays(7), today));

        final long earlyThisMorning = ZonedDateTime.of(2026, 11, 24, 2, 0, 0, 0, KOLKATA).toInstant().toEpochMilli();
        assertEquals(HomeLists.When.TODAY, HomeLists.build(List.of(chat(1, false, "x", earlyThisMorning, false, 0)),
                NOW, KOLKATA, Locale.UK).chats().get(0).when(), "today in Kolkata, though still yesterday in UTC");
        final HomeLists.ChatRow r = HomeLists.build(List.of(chat(1, false, "x", NOW - 24 * HOUR, false, 0)),
                NOW, KOLKATA, Locale.UK).chats().get(0);
        assertEquals(HomeLists.When.YESTERDAY, r.when());
        assertEquals("", r.timeLabel(), "the word comes from the screen's strings");

        final HomeLists.ChatRow sunday = HomeLists.build(List.of(chat(1, false, "x", NOW - 2 * 24 * HOUR, false, 0)),
                NOW, KOLKATA, Locale.UK).chats().get(0);
        assertEquals("Sun", sunday.timeLabel());
        final HomeLists.ChatRow old = HomeLists.build(List.of(chat(1, false, "x", NOW - 30 * 24 * HOUR, false, 0)),
                NOW, KOLKATA, Locale.UK).chats().get(0);
        assertEquals("25 Oct 2026", old.timeLabel());
        final HomeLists.ChatRow now = HomeLists.build(List.of(chat(1, false, "x", NOW, false, 0)),
                NOW, KOLKATA, Locale.UK).chats().get(0);
        assertEquals("14:00", now.timeLabel());
    }

    @Test
    void theUnreadBadgeCapsAtNinetyNinePlus() {
        final List<HomeLists.ChatRow> rows = HomeLists.build(List.of(chat(1, false, "a", NOW, false, 99),
                chat(2, false, "b", NOW, false, 100)), NOW, KOLKATA, Locale.UK).chats();
        assertFalse(rows.get(0).unreadCapped());
        assertTrue(rows.get(1).unreadCapped());
        assertEquals(100, rows.get(1).unread(), "the real count is kept for the spoken sentence");
    }
}

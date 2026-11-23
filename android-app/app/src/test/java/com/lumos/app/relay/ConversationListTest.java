/*
 * Lumos - android-app/app / Tests: the conversation summaries behind the Matches and Chats tabs.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-005, TRS-TST-006
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: Real chat stores on SQLite files through FakeRelay, as in
 * RelayReceiptsTest. A match with nothing said is a summary with no last
 * message; the peer's messages count as unread until markRead; the newest
 * activity comes first; and an ended conversation is gone from the list.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.chat.ConversationList;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.match.MatchMachine;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The conversation list, end to end. */
public class ConversationListTest {

    private static final SecureRandom RNG = new SecureRandom();
    private static final MatchMachine.State MATCHED = MatchMachine.State.MATCHED;

    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final FakeRelay relay = new FakeRelay(clock);
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    private static byte[] random(final int n) {
        final byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static RemoteText.TextEvent text(final String s) {
        return new RemoteText.TextEvent(TextEventKind.TEXT, null, s.getBytes(StandardCharsets.UTF_8));
    }

    private RelayDevice device() throws Exception {
        final RelayDevice d = new RelayDevice(clock, relay, open);
        d.sync.run();
        return d;
    }

    private byte[] match(final RelayDevice a, final RelayDevice b) throws Exception {
        final byte[] m = random(16);
        a.chat.acceptConversation(m, b.edPub);
        b.chat.acceptConversation(m, a.edPub);
        b.exchange.take(m, MATCHED, a.exchange.offer(m, MATCHED).orElseThrow());
        a.exchange.take(m, MATCHED, b.exchange.offer(m, MATCHED).orElseThrow());
        a.sync.run();
        b.sync.run();
        assertTrue(a.sessions.start(m, b.edPub));
        return m;
    }

    @Test
    void newMatchesUnreadCountsAndTheNewestFirst() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final RelayDevice cara = device();
        final byte[] quiet = match(cara, bob);
        final byte[] talking = match(alice, bob);
        final ConversationList list = ConversationList.over(bob.store, bob.chat);

        List<ConversationList.Summary> all = list.all();
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(s -> s.last().isEmpty()), "two new matches, nothing said");

        alice.chat.send(talking, text("Jazz at nine?"));
        clock.advance(1_000);
        alice.chat.send(talking, text("I'm by the bar"));
        alice.sync.run();
        assertEquals(2, bob.sync.run().shown());

        all = list.all();
        assertArrayEquals(talking, all.get(0).matchId(), "the chat with something said comes first");
        assertEquals("I'm by the bar", new String(all.get(0).last().orElseThrow().body(), StandardCharsets.UTF_8));
        assertEquals(2, all.get(0).unread());
        assertArrayEquals(quiet, all.get(1).matchId());
        assertFalse(all.get(1).last().isPresent());

        bob.chat.markRead(talking, true);
        assertEquals(0, list.all().get(0).unread(), "read once the conversation is opened");
        bob.chat.send(talking, text("On my way"));
        assertEquals(0, list.all().get(0).unread(), "your own messages are never unread");
        assertTrue(list.all().get(0).last().orElseThrow().outgoing());
    }

    @Test
    void anEndedConversationLeavesTheList() throws Exception {
        final RelayDevice alice = device();
        final RelayDevice bob = device();
        final byte[] m = match(alice, bob);
        final ConversationList list = ConversationList.over(bob.store, bob.chat);
        assertEquals(1, list.all().size());
        bob.safety.end(m, ConversationSafety.Ending.UNMATCH);
        assertTrue(list.all().isEmpty());
    }
}

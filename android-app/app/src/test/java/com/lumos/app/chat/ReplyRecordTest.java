/*
 * Lumos - android-app/app / Tests: the stored message's optional reply link.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-011, SRS-DAT-002, TRS-TST-001
 * ADRs: ADR-003
 * Threading: test-only
 * Notes: The link rides last and only when present, so a row written before
 * CR-009 reads exactly as before (answering nothing) and a reply survives a
 * round trip with its link. A link that is not a whole message id is a
 * corrupt row, not a reply to something else.
 */

package com.lumos.app.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.store.StoreException;
import com.lumos.protocol.chat.DeliveryMachine;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReplyRecordTest {

    private static ChatRecords.Message message(final byte[] replyTo) {
        return new ChatRecords.Message(ChatRecords.newMessageId(), ChatRecords.newMessageId(), true,
                "Saturday!".getBytes(StandardCharsets.UTF_8), 1_700_000_000_000L, 3, DeliveryMachine.State.SENT, false,
                false, new byte[0], new byte[0], replyTo);
    }

    @Test
    void aRowWithoutTheLinkReadsAsBeforeAndAReplyKeepsItsLink() throws StoreException {
        final ChatRecords.Message plain = message(new byte[0]);
        final byte[] row = plain.encode();
        assertFalse(ChatRecords.Message.decode(row).isReply(), "a pre-CR-009 row answers nothing");
        assertArrayEquals(row, new ChatRecords.Message(plain.messageId(), plain.matchId(), true, plain.body(),
                plain.createdAtMillis(), 3, DeliveryMachine.State.SENT, false, false, new byte[0], new byte[0], new byte[0])
                .encode(),
                "and a message that answers nothing is written byte for byte as before");

        final byte[] answered = ChatRecords.newMessageId();
        final ChatRecords.Message back = ChatRecords.Message.decode(message(answered).encode());
        assertTrue(back.isReply());
        assertArrayEquals(answered, back.replyTo());
        assertArrayEquals(answered, back.withState(DeliveryMachine.State.READ).replyTo(), "state changes keep it");
        assertArrayEquals(answered, back.withReaction(false, "❤".getBytes(StandardCharsets.UTF_8)).replyTo());
        assertFalse(back.withDeleted().isReply(), "a deleted reply keeps nothing, not even what it answered");
    }

    @Test
    void aLinkThatIsNotAWholeMessageIdIsACorruptRow() {
        final byte[] row = message(ChatRecords.newMessageId()).encode();
        final byte[] cut = Arrays.copyOf(row, row.length - 1);
        cut[cut.length - 16] = 15; // the length prefix now claims 15 bytes
        assertThrows(StoreException.class, () -> ChatRecords.Message.decode(cut));
    }
}

/*
 * Lumos - android-app/app / The chat path's record contents and their byte encodings.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-LOC-001, TRS-LOC-004, TRS-LOC-005, TRS-PAT-004, SRS-RMT-004, SRS-RMT-017
 * ADRs: ADR-003
 * Threading: any
 * Notes: What each schema-v6 row holds, before LocalStore seals it. Every
 * encoding starts with a format byte so a later build can read an older row
 * (TRS-LOC-003), and every decoder checks lengths before copying. Message ids
 * are random version-4 UUIDs, exactly MESSAGE_ID_LEN_BYTES long, which is why
 * the row id of a message, its outbox item and its dedup entry can simply be
 * the id's UUID string (TRS-LOC-010): it derives from nothing.
 */

package com.lumos.app.chat;

import com.lumos.app.store.StoreException;
import com.lumos.protocol.chat.DeliveryMachine;
import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** The chat records and their encodings. */
public final class ChatRecords {

    private static final int FORMAT = 1;
    private static final int ID_LEN = 16;
    private static final int KEY_LEN = 32;

    private ChatRecords() {
        throw new AssertionError("no instances");
    }

    /**
     * The UUID string of a 16-byte message id: the row id of its records.
     *
     * @param messageId the message id
     * @return the canonical UUID string
     */
    public static String rowId(final byte[] messageId) {
        final ByteBuffer b = ByteBuffer.wrap(messageId);
        return new UUID(b.getLong(), b.getLong()).toString();
    }

    /**
     * A fresh random message id (a version-4 UUID's 16 bytes).
     *
     * @return the id
     */
    public static byte[] newMessageId() {
        final UUID u = UUID.randomUUID();
        return ByteBuffer.allocate(ID_LEN).putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits()).array();
    }

    private static StoreException corrupt() {
        return new StoreException(StoreException.Reason.ENGINE, "chat record is malformed");
    }

    private static void writeBytes(final ByteArrayOutputStream out, final byte[] b) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(b.length).array());
        out.writeBytes(b);
    }

    private static byte[] readBytes(final ByteBuffer in) {
        final int n = in.getInt();
        if (n < 0 || n > in.remaining()) {
            throw new BufferUnderflowException();
        }
        final byte[] b = new byte[n];
        in.get(b);
        return b;
    }

    private static byte[] fixed(final ByteBuffer in, final int n) {
        final byte[] b = new byte[n];
        in.get(b);
        return b;
    }

    private static ByteBuffer open(final byte[] b) throws StoreException {
        final ByteBuffer in = ByteBuffer.wrap(b);
        if (!in.hasRemaining() || in.get() != FORMAT) {
            throw corrupt();
        }
        return in;
    }

    /**
     * A conversation with a matched peer.
     *
     * @param matchId the match identifier
     * @param peerIdentityEd the peer's Ed25519 identity, pinned at the match
     */
    public record Conversation(byte[] matchId, byte[] peerIdentityEd) {

        /**
         * Copies.
         *
         * @param matchId see the record
         * @param peerIdentityEd see the record
         */
        public Conversation {
            matchId = matchId.clone();
            peerIdentityEd = peerIdentityEd.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }

        /** @return a copy of the peer identity */
        @Override
        public byte[] peerIdentityEd() {
            return peerIdentityEd.clone();
        }

        /** @return the record bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(FORMAT);
            writeBytes(out, matchId);
            writeBytes(out, peerIdentityEd);
            return out.toByteArray();
        }

        /**
         * @param b the record bytes
         * @return the conversation
         * @throws StoreException when the bytes are not one
         */
        public static Conversation decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = open(b);
                return new Conversation(readBytes(in), readBytes(in));
            } catch (final BufferUnderflowException e) {
                throw corrupt();
            }
        }
    }

    /**
     * A conversation's ratchet and, until the peer first replies, the PREKEY
     * fields every envelope must carry.
     *
     * @param ratchetState the exported ratchet state (key material; sealed at rest)
     * @param prekeyFields the encoded PREKEY fields, or empty once the peer replied
     */
    public record Session(byte[] ratchetState, byte[] prekeyFields) {

        /**
         * Copies.
         *
         * @param ratchetState see the record
         * @param prekeyFields see the record
         */
        public Session {
            ratchetState = ratchetState.clone();
            prekeyFields = prekeyFields.clone();
        }

        /** @return a copy of the ratchet state */
        @Override
        public byte[] ratchetState() {
            return ratchetState.clone();
        }

        /** @return a copy of the PREKEY fields, empty once the peer replied */
        @Override
        public byte[] prekeyFields() {
            return prekeyFields.clone();
        }

        /** @return the record bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(FORMAT);
            writeBytes(out, ratchetState);
            writeBytes(out, prekeyFields);
            return out.toByteArray();
        }

        /**
         * @param b the record bytes
         * @return the session
         * @throws StoreException when the bytes are not one
         */
        public static Session decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = open(b);
                return new Session(readBytes(in), readBytes(in));
            } catch (final BufferUnderflowException e) {
                throw corrupt();
            }
        }
    }

    /**
     * One visible message, with the events applied to it.
     *
     * @param messageId the message id
     * @param matchId the conversation
     * @param outgoing whether this device sent it
     * @param body the text, empty once deleted for everyone
     * @param createdAtMillis UTC creation time
     * @param sequence this device's order of the message in its conversation
     * @param state the delivery state (outgoing only)
     * @param edited whether an edit was applied
     * @param deleted whether it was deleted for everyone
     * @param myReaction this device's reaction, or empty
     * @param peerReaction the peer's reaction, or empty
     */
    public record Message(byte[] messageId, byte[] matchId, boolean outgoing, byte[] body, long createdAtMillis,
            long sequence, DeliveryMachine.State state, boolean edited, boolean deleted, byte[] myReaction, byte[] peerReaction) {

        /**
         * Copies.
         *
         * @param messageId see the record
         * @param matchId see the record
         * @param outgoing see the record
         * @param body see the record
         * @param createdAtMillis see the record
         * @param sequence see the record
         * @param state see the record
         * @param edited see the record
         * @param deleted see the record
         * @param myReaction see the record
         * @param peerReaction see the record
         */
        public Message {
            messageId = messageId.clone();
            matchId = matchId.clone();
            body = body.clone();
            myReaction = myReaction.clone();
            peerReaction = peerReaction.clone();
        }

        /** @return a copy of the message id */
        @Override
        public byte[] messageId() {
            return messageId.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }

        /** @return a copy of the body */
        @Override
        public byte[] body() {
            return body.clone();
        }

        /** @return a copy of this device's reaction */
        @Override
        public byte[] myReaction() {
            return myReaction.clone();
        }

        /** @return a copy of the peer's reaction */
        @Override
        public byte[] peerReaction() {
            return peerReaction.clone();
        }

        /**
         * @param next the new delivery state
         * @return this message in that state
         */
        public Message withState(final DeliveryMachine.State next) {
            return new Message(messageId, matchId, outgoing, body, createdAtMillis, sequence, next, edited, deleted,
                    myReaction, peerReaction);
        }

        /**
         * @param newBody the edited text
         * @return this message, edited
         */
        public Message withEdit(final byte[] newBody) {
            return new Message(messageId, matchId, outgoing, newBody, createdAtMillis, sequence, state, true, deleted,
                    myReaction, peerReaction);
        }

        /** @return this message, deleted for everyone: the body is gone */
        public Message withDeleted() {
            return new Message(messageId, matchId, outgoing, new byte[0], createdAtMillis, sequence, state, edited, true,
                    myReaction, peerReaction);
        }

        /**
         * @param mine whether this device reacted
         * @param reaction the reaction
         * @return this message with the reaction set
         */
        public Message withReaction(final boolean mine, final byte[] reaction) {
            return new Message(messageId, matchId, outgoing, body, createdAtMillis, sequence, state, edited, deleted,
                    mine ? reaction : myReaction, mine ? peerReaction : reaction);
        }

        /** @return the record bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(FORMAT);
            out.writeBytes(messageId);
            writeBytes(out, matchId);
            out.write(outgoing ? 1 : 0);
            writeBytes(out, body);
            out.writeBytes(ByteBuffer.allocate(2 * Long.BYTES).putLong(createdAtMillis).putLong(sequence).array());
            out.write(state.ordinal());
            out.write((edited ? 1 : 0) | (deleted ? 2 : 0));
            writeBytes(out, myReaction);
            writeBytes(out, peerReaction);
            return out.toByteArray();
        }

        /**
         * @param b the record bytes
         * @return the message
         * @throws StoreException when the bytes are not one
         */
        public static Message decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = open(b);
                final byte[] id = fixed(in, ID_LEN);
                final byte[] match = readBytes(in);
                final boolean out = in.get() == 1;
                final byte[] body = readBytes(in);
                final long created = in.getLong();
                final long sequence = in.getLong();
                final int stateIndex = in.get();
                final DeliveryMachine.State[] states = DeliveryMachine.State.values();
                if (stateIndex < 0 || stateIndex >= states.length) {
                    throw corrupt();
                }
                final int flags = in.get();
                return new Message(id, match, out, body, created, sequence, states[stateIndex], (flags & 1) != 0,
                        (flags & 2) != 0, readBytes(in), readBytes(in));
            } catch (final BufferUnderflowException e) {
                throw corrupt();
            }
        }
    }

    /**
     * A send waiting for its transport: a TRS-PAT-004 command whose
     * idempotency key is the message id. The envelope is sealed ONCE, when the
     * message is created, so every retry sends the same bytes and the relay
     * recognises it as the same message.
     *
     * @param messageId the idempotency key
     * @param matchId the conversation
     * @param envelope the sealed relay payload
     * @param createdAtMillis UTC enqueue time
     * @param sequence the device-wide enqueue order: the outbox is first in, first out
     * @param attempts transport attempts so far
     */
    public record OutboxItem(byte[] messageId, byte[] matchId, byte[] envelope, long createdAtMillis, long sequence,
            int attempts) {

        /**
         * Copies.
         *
         * @param messageId see the record
         * @param matchId see the record
         * @param envelope see the record
         * @param createdAtMillis see the record
         * @param sequence see the record
         * @param attempts see the record
         */
        public OutboxItem {
            messageId = messageId.clone();
            matchId = matchId.clone();
            envelope = envelope.clone();
        }

        /** @return a copy of the message id */
        @Override
        public byte[] messageId() {
            return messageId.clone();
        }

        /** @return a copy of the match id */
        @Override
        public byte[] matchId() {
            return matchId.clone();
        }

        /** @return a copy of the envelope */
        @Override
        public byte[] envelope() {
            return envelope.clone();
        }

        /** @return the record bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(FORMAT);
            out.writeBytes(messageId);
            writeBytes(out, matchId);
            writeBytes(out, envelope);
            out.writeBytes(ByteBuffer.allocate(2 * Long.BYTES + Integer.BYTES).putLong(createdAtMillis)
                    .putLong(sequence).putInt(attempts).array());
            return out.toByteArray();
        }

        /**
         * @param b the record bytes
         * @return the item
         * @throws StoreException when the bytes are not one
         */
        public static OutboxItem decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = open(b);
                return new OutboxItem(fixed(in, ID_LEN), readBytes(in), readBytes(in), in.getLong(), in.getLong(),
                        in.getInt());
            } catch (final BufferUnderflowException e) {
                throw corrupt();
            }
        }
    }

    /**
     * A private prekey scalar.
     *
     * @param oneTime true for a one-time prekey, false for a signed prekey
     * @param id the prekey id
     * @param privateKey the X25519 scalar
     */
    public record PrekeySecret(boolean oneTime, long id, byte[] privateKey) {

        /**
         * Copies.
         *
         * @param oneTime see the record
         * @param id see the record
         * @param privateKey see the record
         */
        public PrekeySecret {
            if (privateKey.length != KEY_LEN) {
                throw new IllegalArgumentException("a prekey scalar is 32 bytes");
            }
            privateKey = privateKey.clone();
        }

        /** @return a copy of the scalar */
        @Override
        public byte[] privateKey() {
            return privateKey.clone();
        }

        /**
         * The blind-index input: kind and id.
         *
         * @param oneTime the prekey's kind
         * @param id its id
         * @return the natural key
         */
        public static byte[] naturalKey(final boolean oneTime, final long id) {
            return ByteBuffer.allocate(1 + Integer.BYTES).put((byte) (oneTime ? 1 : 0)).putInt((int) id).array();
        }

        /** @return the record bytes */
        public byte[] encode() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(FORMAT);
            out.write(oneTime ? 1 : 0);
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt((int) id).array());
            out.writeBytes(privateKey);
            return out.toByteArray();
        }

        /**
         * @param b the record bytes
         * @return the secret
         * @throws StoreException when the bytes are not one
         */
        public static PrekeySecret decode(final byte[] b) throws StoreException {
            try {
                final ByteBuffer in = open(b);
                final boolean oneTime = in.get() == 1;
                final long id = in.getInt() & 0xFFFFFFFFL;
                return new PrekeySecret(oneTime, id, fixed(in, KEY_LEN));
            } catch (final BufferUnderflowException e) {
                throw corrupt();
            }
        }
    }

    /**
     * Encodes a dedup entry's expiry.
     *
     * @param expiresAtMillis when the entry may be forgotten
     * @return the record bytes
     */
    public static byte[] encodeDedup(final long expiresAtMillis) {
        return ByteBuffer.allocate(1 + Long.BYTES).put((byte) FORMAT).putLong(expiresAtMillis).array();
    }

    /**
     * @param b the record bytes
     * @return the expiry
     * @throws StoreException when the bytes are not one
     */
    public static long decodeDedup(final byte[] b) throws StoreException {
        try {
            return open(b).getLong();
        } catch (final BufferUnderflowException e) {
            throw corrupt();
        }
    }

    /**
     * Whether two ids are the same message.
     *
     * @param a one id
     * @param b another
     * @return true when equal
     */
    public static boolean sameId(final byte[] a, final byte[] b) {
        return Arrays.equals(a, b);
    }
}

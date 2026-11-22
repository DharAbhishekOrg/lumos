/*
 * Lumos - android-app/app / A private prekey scalar this device published, as stored.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-CRY-005, TRS-LOC-001
 * ADRs: ADR-001, ADR-003
 * Threading: any
 * Notes: Kept in PREKEY_SECRET, sealed like every row and found by a blind
 * index over its kind and id. A one-time prekey's row is deleted in the same
 * transaction that opens the first message it agreed (TRS-CRY-005). Split from
 * ChatRecords to keep that file inside the TRS-CODE-004 class budget.
 */

package com.lumos.app.chat;

import com.lumos.app.store.StoreException;
import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

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
        if (privateKey.length != ChatRecords.KEY_LEN) {
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
        out.write(ChatRecords.FORMAT);
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
            final ByteBuffer in = ChatRecords.open(b);
            final boolean oneTime = in.get() == 1;
            final long id = in.getInt() & 0xFFFFFFFFL;
            return new PrekeySecret(oneTime, id, ChatRecords.fixed(in, ChatRecords.KEY_LEN));
        } catch (final BufferUnderflowException e) {
            throw ChatRecords.corrupt();
        }
    }
}

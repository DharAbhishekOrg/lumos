/*
 * Lumos - android-app/protocol-java / Delivery and read receipts, sealed in the chat's ratchet and bound to their class.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-014, SRS-RMT-004, SRS-RMT-009, TRS-PROTO-005, TRS-PROTO-007
 * ADRs: ADR-001, ADR-008
 * Threading: serial-executor
 * Notes: Mirrors protocol/chat/receipt.go and is held to chat/receipts.json
 * byte for byte. CR-005 (owner-accepted 2026-11-23) keeps the TRS Sec. 9.2
 * classes 0x11 and 0x12. The plaintext is receipt_id[16] | kind u8 |
 * count u8 | message_id[16] x count; kind must equal the class the relay
 * delivered it under and receipt_id the relay's id, so a relay can neither
 * turn "delivered" into "read" nor relabel a receipt. Text event kinds 17
 * and 18 are reserved, so a receipt passed off as text decodes as an unknown
 * kind and shows nothing. An encoder sends at most
 * RECEIPT_IDS_PER_ENVELOPE.default ids; a decoder accepts up to .max.
 */

package com.lumos.protocol.chat;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.ratchet.DoubleRatchet;
import com.lumos.protocol.wire.Frame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One receipt.
 *
 * @param id the receipt's own id (the relay message id it travels under)
 * @param kind DELIVERY_RECEIPT or READ_RECEIPT
 * @param acked the acknowledged message ids
 */
public record Receipt(byte[] id, MessageClass kind, List<byte[]> acked) {

    private static final int ID = RemoteText.MESSAGE_ID_SIZE;
    private static final int HEADER = ID + 2;
    private static final int BYTE_MASK = 0xFF;

    /**
     * Copies.
     *
     * @param id see the record
     * @param kind see the record
     * @param acked see the record
     */
    public Receipt {
        if (id.length != ID) {
            throw new IllegalArgumentException("a receipt id is " + ID + " bytes");
        }
        id = id.clone();
        final List<byte[]> copy = new ArrayList<>();
        for (final byte[] a : acked) {
            if (a.length != ID) {
                throw new IllegalArgumentException("an acknowledged id is " + ID + " bytes");
            }
            copy.add(a.clone());
        }
        acked = Collections.unmodifiableList(copy);
    }

    /** @return a copy of the receipt id */
    @Override
    public byte[] id() {
        return id.clone();
    }

    /** @return copies of the acknowledged ids */
    @Override
    public List<byte[]> acked() {
        final List<byte[]> out = new ArrayList<>();
        for (final byte[] a : acked) {
            out.add(a.clone());
        }
        return out;
    }

    private static Frame.WireException malformed(final String detail) {
        return new Frame.WireException(LumosErrors.ERR_PAYLOAD_MALFORMED, detail);
    }

    private static boolean isReceiptKind(final MessageClass k) {
        return k == MessageClass.DELIVERY_RECEIPT || k == MessageClass.READ_RECEIPT;
    }

    /**
     * Renders receipt_id | kind | count | ids.
     *
     * @return the plaintext
     * @throws Frame.WireException when the kind is not a receipt or the count is out of range
     */
    public byte[] encode() throws Frame.WireException {
        if (!isReceiptKind(kind)) {
            throw malformed("not a receipt class");
        }
        if (acked.isEmpty() || acked.size() > LumosConstants.RECEIPT_IDS_PER_ENVELOPE_DEFAULT) {
            throw malformed("receipt id count out of range");
        }
        final byte[] out = new byte[HEADER + ID * acked.size()];
        System.arraycopy(id, 0, out, 0, ID);
        out[ID] = (byte) kind.code();
        out[ID + 1] = (byte) acked.size();
        for (int i = 0; i < acked.size(); i++) {
            System.arraycopy(acked.get(i), 0, out, HEADER + i * ID, ID);
        }
        return out;
    }

    /**
     * Parses a receipt plaintext, refusing trailing bytes.
     *
     * @param b the plaintext
     * @return the receipt
     * @throws Frame.WireException ERR_PAYLOAD_MALFORMED on anything outside the layout
     */
    public static Receipt decode(final byte[] b) throws Frame.WireException {
        if (b.length < HEADER) {
            throw malformed("receipt shorter than its header");
        }
        final MessageClass kind = MessageClass.fromCode(b[ID] & BYTE_MASK);
        if (!isReceiptKind(kind)) {
            throw malformed("not a receipt class");
        }
        final int n = b[ID + 1] & BYTE_MASK;
        if (n == 0 || n > LumosConstants.RECEIPT_IDS_PER_ENVELOPE_MAX) {
            throw malformed("receipt id count out of range");
        }
        if (b.length - HEADER != n * ID) {
            throw malformed("receipt length does not match its count");
        }
        final List<byte[]> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(Arrays.copyOfRange(b, HEADER + i * ID, HEADER + (i + 1) * ID));
        }
        return new Receipt(Arrays.copyOf(b, ID), kind, ids);
    }

    /**
     * Seals this receipt into an envelope, exactly as RemoteSession.seal does text.
     *
     * @param ratchet the session
     * @param prekey the PREKEY fields while the peer has not replied, else null
     * @param now the caller's clock
     * @return the envelope bytes
     * @throws Frame.WireException on an encoding or sealing failure
     */
    public byte[] seal(final DoubleRatchet ratchet, final RemoteText.PrekeyFields prekey, final long now)
            throws Frame.WireException {
        final DoubleRatchet.Sealed s = ratchet.encrypt(encode(), now);
        return new RemoteText.Envelope(prekey, s.header(), s.ciphertext()).encode();
    }

    /**
     * Opens one envelope delivered under outerId and outerClass.
     *
     * @param ratchet the session
     * @param e the envelope
     * @param outerId the id the relay delivered it under
     * @param outerClass the class the relay delivered it under
     * @param now the caller's clock
     * @param kp fresh ratchet keys
     * @return the receipt
     * @throws Frame.WireException an authentication, replay, format or relabelling failure
     */
    public static Receipt open(final DoubleRatchet ratchet, final RemoteText.Envelope e, final byte[] outerId,
            final MessageClass outerClass, final long now, final DoubleRatchet.KeyProvider kp)
            throws Frame.WireException {
        final Receipt r = decode(ratchet.decrypt(e.header(), e.ciphertext(), now, kp));
        if (!Arrays.equals(r.id, outerId)) {
            throw malformed("authenticated receipt id differs from the delivered id");
        }
        if (r.kind != outerClass) {
            throw malformed("authenticated receipt kind differs from the delivered class");
        }
        return r;
    }
}

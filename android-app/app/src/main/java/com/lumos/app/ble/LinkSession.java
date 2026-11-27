/*
 * Lumos - android-app/app / One discovery link: both HELLOs, bounded, over frames.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, TRS-ENV-002, SRS-IDN-002, SRS-NBY-005, SRS-SAF-002
 * ADRs: ADR-002, ADR-008, ADR-013
 * Threading: serial-executor (the link's)
 * Notes: The protocol half of ADR-013's link, with no radio in it: what to send
 * when the link opens, what each arriving frame means, and when the link is
 * finished. Both sides run their own HelloExchange (each grants presence only
 * for a verified ACK to its OWN hello), so a link ends DONE only when this side
 * has (a) answered the peer's HELLO and (b) verified the ACK to its own. The
 * Peer this yields is the only object PresenceTable accepts (TRS-ENV-002).
 *
 * Bounded and quiet: a frame that does not decode, a class other than HELLO or
 * HELLO_ACK, a second HELLO, an ACK that does not verify (a replay, a forgery, a
 * mirror of our own identity) or the handshake timer ending turns the link
 * FAILED, grants nothing, and keeps only the stable error code (or that it
 * timed out) - never the peer's bytes or identity.
 *
 * Blocking applies here (SRS-SAF-002, local discovery): a HELLO or ACK from an
 * identity the person blocked is never answered or recorded. The link ends
 * REFUSED-and-FAILED with no wire code and nothing sent, so the blocked side
 * gets no ACK to its own HELLO and therefore never a card. The radio adapters (GATT client and server) carry
 * blobs in and out of this class through the Outbox, once CR-010 has given
 * the characteristics their identifiers; tests carry them between two
 * sessions directly.
 */

package com.lumos.app.ble;

import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;
import com.lumos.protocol.wire.Frame;
import java.security.GeneralSecurityException;

/** One discovery link's handshake. */
public final class LinkSession {

    /** Which end of the GATT connection this side is (ADR-013 §3/§4). */
    public enum Role {
        /** Connected out to the peer, writes to LINK_IN, hears LINK_OUT. */
        CENTRAL,
        /** Hosts the service, hears LINK_IN, notifies LINK_OUT. */
        PERIPHERAL
    }

    /** Where the link stands. */
    public enum State {
        /** Not opened yet: the central before subscribing, the peripheral before being subscribed. */
        WAITING,
        /** Own HELLO sent; waiting for the peer's HELLO and the ACK to ours. */
        EXCHANGING,
        /** Both done: the peer's HELLO answered, the ACK to ours verified. */
        DONE,
        /** Ended without presence. */
        FAILED
    }

    /** Sends one encoded frame on the link. */
    public interface Outbox {
        /**
         * @param frame the encoded frame
         */
        void send(byte[] frame);
    }

    /** Identities this side will not complete a handshake with. */
    public interface Refusals {
        /** Refuses nobody. */
        Refusals NONE = peerKey -> false;

        /**
         * @param peerKey the claimed peer's card key (PresenceTable.keyFor)
         * @return true to end the link without answering
         */
        boolean refuses(String peerKey);
    }

    /** Fresh HELLO nonces. */
    public interface Nonces {
        /**
         * @return a fresh nonce of the HELLO layout's length
         */
        byte[] next();
    }

    private final Role role;
    private final HelloExchange exchange;
    private final Outbox outbox;
    private final Nonces nonces;
    private final long timeoutMillis;
    private final Refusals refusals;
    private State state = State.WAITING;
    private long openedAtMillis;
    private boolean answered;
    private HelloExchange.Peer peer;
    private String failure;
    private boolean timedOut;
    private boolean refused;
    private long sequence;

    /**
     * @param role this side's end of the connection
     * @param exchange this side's HELLO exchange (own identity and signer)
     * @param outbox where frames go
     * @param nonces fresh nonces
     * @param timeoutMillis how long the handshake may take once opened
     */
    public LinkSession(final Role role, final HelloExchange exchange, final Outbox outbox, final Nonces nonces,
            final long timeoutMillis) {
        this(role, exchange, outbox, nonces, timeoutMillis, Refusals.NONE);
    }

    /**
     * @param role this side's end of the connection
     * @param exchange this side's HELLO exchange (own identity and signer)
     * @param outbox where frames go
     * @param nonces fresh nonces
     * @param timeoutMillis how long the handshake may take once opened
     * @param refusals identities never answered (the block list)
     */
    public LinkSession(final Role role, final HelloExchange exchange, final Outbox outbox, final Nonces nonces,
            final long timeoutMillis, final Refusals refusals) {
        this.role = role;
        this.exchange = exchange;
        this.outbox = outbox;
        this.nonces = nonces;
        this.timeoutMillis = timeoutMillis;
        this.refusals = refusals;
    }

    /** @return this side's role */
    public Role role() {
        return role;
    }

    /** @return where the link stands */
    public State state() {
        return state;
    }

    /** @return the verified peer once DONE, else null */
    public HelloExchange.Peer peer() {
        return state == State.DONE ? peer : null;
    }

    /** @return the stable wire error code a refused link ended with, else null */
    public String failure() {
        return failure;
    }

    /** @return true when the link ended because the peer is one this side refuses */
    public boolean refused() {
        return refused;
    }

    /** @return true when the link ended because the handshake timer ran out */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * Opens the link: the central once it has subscribed to LINK_OUT, the
     * peripheral once a central has. Sends this side's HELLO.
     *
     * @param nowMillis when
     */
    public void open(final long nowMillis) {
        if (state != State.WAITING) {
            return;
        }
        openedAtMillis = nowMillis;
        state = State.EXCHANGING;
        send(MessageClass.HELLO, exchange.start(nonces.next()));
    }

    /**
     * One whole frame arrived from the peer (reassembled by the link layer).
     *
     * @param blob the frame bytes
     * @param nowMillis when
     */
    public void onFrame(final byte[] blob, final long nowMillis) {
        if (state == State.DONE || state == State.FAILED) {
            return;
        }
        if (state == State.WAITING) {
            // A peripheral can hear a HELLO before the subscription lands;
            // opening first keeps one order: our HELLO, then everything else.
            open(nowMillis);
        }
        if (expired(nowMillis)) {
            return;
        }
        final Frame frame;
        try {
            frame = Frame.decode(blob);
        } catch (Frame.WireException malformed) {
            fail(malformed.code());
            return;
        }
        final MessageClass kind = frame.header().messageClass();
        if (kind == MessageClass.HELLO) {
            onHello(frame.payload());
        } else if (kind == MessageClass.HELLO_ACK) {
            onAck(frame.payload());
        } else {
            // A discovery link carries the handshake and nothing else.
            fail(LumosErrors.ERR_FRAME_UNKNOWN_TYPE);
        }
    }

    /**
     * Checks the handshake timer.
     *
     * @param nowMillis when
     */
    public void tick(final long nowMillis) {
        expired(nowMillis);
    }

    private boolean expired(final long nowMillis) {
        if (state == State.EXCHANGING && nowMillis - openedAtMillis >= timeoutMillis) {
            fail(null);
            timedOut = true;
            return true;
        }
        return state == State.FAILED;
    }

    private void onHello(final byte[] hello) {
        if (answered) {
            // One HELLO per direction per link: a second is a replay attempt.
            fail(LumosErrors.ERR_CRYPTO_REPLAY);
            return;
        }
        try {
            if (refusals.refuses(PresenceTable.keyFor(Hello.peerId(Hello.parseHello(hello).ed25519Public())))) {
                refuse();
                return;
            }
            send(MessageClass.HELLO_ACK, exchange.onHello(hello));
            answered = true;
        } catch (Frame.WireException bad) {
            fail(bad.code());
            return;
        } catch (GeneralSecurityException signing) {
            fail(LumosErrors.ERR_HELLO_SIGNATURE_INVALID);
            return;
        }
        finishIfDone();
    }

    private void onAck(final byte[] ack) {
        if (peer != null) {
            fail(LumosErrors.ERR_CRYPTO_REPLAY);
            return;
        }
        try {
            peer = exchange.onAck(ack);
        } catch (Frame.WireException unverified) {
            fail(unverified.code());
            return;
        }
        if (refusals.refuses(PresenceTable.keyFor(peer.id()))) {
            refuse();
            return;
        }
        finishIfDone();
    }

    private void finishIfDone() {
        if (state == State.EXCHANGING && answered && peer != null) {
            state = State.DONE;
        }
    }

    private void refuse() {
        refused = true;
        fail(null);
    }

    private void fail(final String code) {
        state = State.FAILED;
        failure = code;
        peer = null;
    }

    private void send(final MessageClass kind, final byte[] payload) {
        final Frame.Header header = new Frame.Header();
        header.type = kind.code();
        header.sequence = ++sequence;
        header.messageId[0] = (byte) (sequence >> 8);
        header.messageId[1] = (byte) sequence;
        try {
            outbox.send(Frame.encode(header, payload));
        } catch (Frame.WireException unsendable) {
            fail(unsendable.code());
        }
    }
}

/*
 * Lumos - android-app/app / One peer's consent conversation, over real frames.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-MAT-001, SRS-MAT-002, SRS-MAT-004, SRS-MAT-005, SRS-MAT-006,
 *             SRS-MAT-009, SRS-MAT-014, TRS-ARCH-009
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: The coordinator AT-005's logic half runs through: local actions and
 * inbound canonical frames become typed machine events (TRS-ARCH-009), the
 * ledger decides and persists, and the effect becomes at most one outbound
 * frame - SEND_REQUEST carries the REQUESTER-audience preview, an accept
 * carries ACCEPT, an unmatch carries UNMATCH, and a decline or ignore
 * carries nothing because there is nothing to carry it in (SRS-MAT-004).
 * Every other class, in or out, goes through ConsentGate against the peer's
 * persisted state: no conversation frame leaves or lands before MATCHED
 * (SRS-MAT-006), whoever forged it. Malformed frames are refused with their
 * stable code and change no state. The link is the Outbox seam - FrameLink
 * on a device, a loopback queue in the AT-005 test.
 */

package com.lumos.app.match;

import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.MessageClass;
import com.lumos.protocol.match.ConsentGate;
import com.lumos.protocol.match.InterestWire;
import com.lumos.protocol.match.MatchMachine;
import com.lumos.protocol.wire.Frame;

/** Couples one peer's interest ledger to the frame link. */
public final class InterestSession {

    /** Where outbound frames go. */
    public interface Outbox {
        /**
         * Sends one encoded canonical frame.
         *
         * @param frame the encoded frame
         */
        void send(byte[] frame);
    }

    /**
     * What happened to one inbound frame.
     *
     * @param delivered whether the frame's content may reach the user
     * @param refusalCode the stable code when refused, otherwise null
     * @param state the peer's state after the frame
     */
    public record Inbound(boolean delivered, String refusalCode, MatchMachine.State state) { }

    private final InterestLedger ledger;
    private final String peerKey;
    private final byte[] requestStagePreview;
    private final Outbox outbox;

    private long sequence;
    private byte[] lastReceivedPreview = new byte[0];

    /**
     * Binds the session.
     *
     * @param ledger the persisted interest ledger
     * @param peerKey the peer's authenticated identifier, hex
     * @param requestStagePreview this user's REQUESTER-audience preview blob
     * @param outbox where outbound frames go
     */
    public InterestSession(final InterestLedger ledger, final String peerKey,
            final byte[] requestStagePreview, final Outbox outbox) {
        this.ledger = ledger;
        this.peerKey = peerKey;
        this.requestStagePreview = requestStagePreview.clone();
        this.outbox = outbox;
    }

    /**
     * Applies a local action and sends whatever its effect names.
     *
     * @param event a LOCAL_* event
     * @param nowMillis the caller's clock
     * @return the ledger's outcome (rate limiting included)
     * @throws StoreException on store failure
     * @throws Frame.WireException if an outbound frame cannot be encoded
     * @throws InterestWire.WireException if the preview cannot be enveloped
     */
    public InterestLedger.Outcome local(final MatchMachine.Event event, final long nowMillis)
            throws StoreException, Frame.WireException, InterestWire.WireException {
        final InterestLedger.Outcome outcome = ledger.apply(peerKey, event, nowMillis);
        switch (outcome.effect()) {
            case SEND_REQUEST:
                emit(MessageClass.INTEREST_REQUEST,
                        InterestWire.encodeRequest(requestStagePreview));
                break;
            case ACCEPT_AND_ESTABLISH:
                emit(MessageClass.INTEREST_RESPONSE,
                        InterestWire.encodeResponse(InterestWire.Verdict.ACCEPT));
                break;
            case SEND_UNMATCH:
                emit(MessageClass.INTEREST_RESPONSE,
                        InterestWire.encodeResponse(InterestWire.Verdict.UNMATCH));
                break;
            default:
                // NONE and MATCH_ESTABLISHED transmit nothing. Declines and
                // ignores always land here: silence is the absence of a case.
                break;
        }
        return outcome;
    }

    /**
     * Sends a conversation-class payload, if the gate allows it.
     *
     * @param messageClass the class to send
     * @param payload its payload
     * @return true when sent; false when the gate refused (not yet matched)
     * @throws StoreException on store failure
     * @throws Frame.WireException if the frame cannot be encoded
     */
    public boolean sendConversation(final MessageClass messageClass, final byte[] payload)
            throws StoreException, Frame.WireException {
        if (!ConsentGate.permits(messageClass, ledger.stateOf(peerKey))) {
            return false;
        }
        emit(messageClass, payload);
        return true;
    }

    /**
     * Handles one inbound frame from this peer.
     *
     * @param encoded the canonical frame bytes
     * @param nowMillis the caller's clock
     * @return whether its content may reach the user, and why not
     * @throws StoreException on store failure
     */
    public Inbound onFrame(final byte[] encoded, final long nowMillis) throws StoreException {
        final Frame frame;
        try {
            frame = Frame.decode(encoded);
        } catch (final Frame.WireException e) {
            return new Inbound(false, e.code(), ledger.stateOf(peerKey));
        }
        final MessageClass mc = frame.header().messageClass();
        if (!ConsentGate.permits(mc, ledger.stateOf(peerKey))) {
            return new Inbound(false, LumosErrors.ERR_MATCH_NOT_MATCHED, ledger.stateOf(peerKey));
        }
        try {
            if (mc == MessageClass.INTEREST_REQUEST) {
                lastReceivedPreview = InterestWire.decodeRequest(frame.payload());
                return new Inbound(true, null, ledger.apply(peerKey,
                        MatchMachine.Event.REMOTE_REQUEST, nowMillis).state());
            }
            if (mc == MessageClass.INTEREST_RESPONSE) {
                final InterestWire.Verdict verdict = InterestWire.decodeResponse(frame.payload());
                final MatchMachine.Event event = verdict == InterestWire.Verdict.ACCEPT
                        ? MatchMachine.Event.REMOTE_ACCEPT : MatchMachine.Event.REMOTE_UNMATCH;
                return new Inbound(true, null, ledger.apply(peerKey, event, nowMillis).state());
            }
        } catch (final InterestWire.WireException e) {
            return new Inbound(false, e.code(), ledger.stateOf(peerKey));
        }
        return new Inbound(true, null, ledger.stateOf(peerKey));
    }

    /** @return the last request-stage preview this peer sent (SRS-MAT-002) */
    public byte[] lastReceivedPreview() {
        return lastReceivedPreview.clone();
    }

    private void emit(final MessageClass messageClass, final byte[] payload)
            throws Frame.WireException {
        final Frame.Header header = new Frame.Header();
        header.type = messageClass.code();
        header.sequence = ++sequence;
        header.messageId[0] = (byte) (sequence >> 8);
        header.messageId[1] = (byte) sequence;
        outbox.send(Frame.encode(header, payload));
    }
}

/*
 * Lumos - android-app/protocol-java / Transport fragmentation: the receiver half.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-010, TRS-TRN-002, TRS-PROTO-007
 * ADRs: ADR-004
 * Threading: serial-executor
 * Notes: The Java port of protocol/wire/fragment.go's Reassembler, held to it
 * step for step by the scripted timelines in
 * test-vectors/framing/fragmentation.json. Single-assembly by design: a
 * serialized GATT link (TRS-TRN-003) carries one message at a time per
 * direction. Duplicate suppression covers a re-sent fragment of the open
 * assembly and a straggler of the completed one - the two shapes a reconnect
 * epoch produces - while whole-message retransmits are deduplicated above this
 * layer by message_id (TRS Sec. 9.4). The reassembly timeout is lazy, applied
 * from the caller-injected clock on the next offer, so the layer owns no timer
 * and every timing behaviour is scriptable. Bounds are validated from the
 * FIRST fragment's declared total BEFORE any allocation (TRS Sec. 9.4).
 */

package com.lumos.protocol.wire;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import java.util.Arrays;

/** Rebuilds one link direction's blobs from fragments (TRS-PROTO-010). */
public final class FragmentReassembler {

    /** What one offered fragment did to the assembly. */
    public enum Outcome {
        /** The fragment advanced an incomplete assembly. */
        ACCEPTED,
        /** The fragment was already consumed and is suppressed. */
        DUPLICATE,
        /** The assembly finished; {@link Offer#payload()} holds the blob. */
        COMPLETE
    }

    /** The report for one successful offer. */
    public static final class Offer {

        private final Outcome outcome;
        private final byte[] payload;
        private final boolean restarted;
        private final boolean expiredPrevious;

        private Offer(final Outcome outcome, final byte[] payload,
                final boolean restarted, final boolean expiredPrevious) {
            this.outcome = outcome;
            this.payload = payload;
            this.restarted = restarted;
            this.expiredPrevious = expiredPrevious;
        }

        /** @return what the fragment did */
        public Outcome outcome() {
            return outcome;
        }

        /** @return a copy of the reassembled blob when {@link Outcome#COMPLETE}, else null */
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }

        /** @return whether a FIRST replaced a live, incomplete assembly */
        public boolean restarted() {
            return restarted;
        }

        /** @return whether the open assembly aged out during this offer */
        public boolean expiredPrevious() {
            return expiredPrevious;
        }
    }

    private final long timeoutMillis;

    private boolean openActive;
    private int openId;
    private int openNext;
    private int openTotal;
    private byte[] openBuf;
    private int openFilled;
    private long openLastAt;

    private boolean completedValid;
    private int completedId;
    private boolean expiredValid;
    private int expiredId;

    /**
     * @param timeoutMillis idle time after which an assembly expires
     *     (REASSEMBLY_TIMEOUT_SEC for the carrying link, in milliseconds)
     */
    public FragmentReassembler(final long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Feeds one received fragment.
     *
     * @param fragment the fragment bytes as received
     * @param nowMillis the caller's clock
     * @return what the fragment did
     * @throws Frame.WireException with the exact stable code for every rule
     *     violation, matching the Go reference and the golden vectors
     */
    public Offer offer(final byte[] fragment, final long nowMillis) throws Frame.WireException {
        if (fragment.length < Fragmenter.HEADER_LEN + 1) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED,
                    "fragment shorter than header plus one content byte");
        }
        final int flags = fragment[0] & 0xFF;
        if ((flags & LumosConstants.FRAG_FLAGS_RESERVED_MASK_DEFAULT) != 0) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED, "reserved flag bit set");
        }
        final int id = fragment[1] & 0xFF;
        final int index = ((fragment[2] & 0xFF) << 8) | (fragment[3] & 0xFF);
        final byte[] content = Arrays.copyOfRange(fragment, Fragmenter.HEADER_LEN, fragment.length);

        final boolean expiredNow = expireIfIdle(nowMillis);

        if ((flags & LumosConstants.FRAG_FLAG_FIRST_DEFAULT) != 0) {
            return offerFirst(id, index, flags, content, nowMillis, expiredNow);
        }
        return offerContinuation(id, index, flags, content, nowMillis, expiredNow);
    }

    /** Lazily applies the reassembly timeout; true when the assembly aged out now. */
    private boolean expireIfIdle(final long nowMillis) {
        if (!openActive || nowMillis - openLastAt <= timeoutMillis) {
            return false;
        }
        expiredValid = true;
        expiredId = openId;
        drop();
        return true;
    }

    private Offer offerFirst(final int id, final int index, final int flags, final byte[] content,
            final long nowMillis, final boolean expiredNow) throws Frame.WireException {
        if (index != 0) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED,
                    "FIRST fragment with a non-zero index");
        }
        if (content.length < Fragmenter.TOTAL_PREFIX_LEN + 1) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED,
                    "FIRST fragment without a total prefix and a content byte");
        }
        final long total = ((long) (content[0] & 0xFF) << 24)
                | ((long) (content[1] & 0xFF) << 16)
                | ((long) (content[2] & 0xFF) << 8)
                | (content[3] & 0xFF);
        if (total == 0) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED, "zero total length");
        }
        if (total > LumosConstants.FRAG_REASSEMBLY_MAX_BYTES_DEFAULT) {
            // Checked before any reassembly allocation (TRS Sec. 9.4).
            throw new Frame.WireException(LumosErrors.ERR_FRAG_TOTAL_TOO_LARGE,
                    "declared total exceeds FRAG_REASSEMBLY_MAX_BYTES");
        }
        final int data = content.length - Fragmenter.TOTAL_PREFIX_LEN;
        if (data > total) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_LENGTH_MISMATCH,
                    "FIRST carries more bytes than the declared total");
        }

        final boolean restarted = openActive;
        openActive = true;
        openId = id;
        openNext = 1;
        openTotal = (int) total;
        openBuf = new byte[openTotal];
        System.arraycopy(content, Fragmenter.TOTAL_PREFIX_LEN, openBuf, 0, data);
        openFilled = data;
        openLastAt = nowMillis;

        if ((flags & LumosConstants.FRAG_FLAG_LAST_DEFAULT) != 0) {
            return complete(restarted, expiredNow);
        }
        return new Offer(Outcome.ACCEPTED, null, restarted, expiredNow);
    }

    private Offer offerContinuation(final int id, final int index, final int flags, final byte[] content,
            final long nowMillis, final boolean expiredNow) throws Frame.WireException {
        if (!openActive || id != openId) {
            if (completedValid && id == completedId) {
                // A straggler of the completed assembly: a reconnect epoch
                // re-sent it. Suppressed, never an error (TRS-PROTO-010).
                return new Offer(Outcome.DUPLICATE, null, false, expiredNow);
            }
            if (expiredValid && id == expiredId) {
                throw new Frame.WireException(LumosErrors.ERR_FRAG_REASSEMBLY_TIMEOUT,
                        "continuation of an assembly that aged out");
            }
            throw new Frame.WireException(LumosErrors.ERR_FRAG_SEQUENCE_GAP,
                    "continuation of an assembly this receiver does not have");
        }

        if (index < openNext) {
            // Already consumed: a link-level retransmit. Suppressed without
            // refreshing the idle clock - a duplicate is not progress.
            return new Offer(Outcome.DUPLICATE, null, false, expiredNow);
        }
        if (index > openNext) {
            drop();
            throw new Frame.WireException(LumosErrors.ERR_FRAG_SEQUENCE_GAP,
                    "a fragment index was skipped");
        }
        if (openFilled + content.length > openTotal) {
            drop();
            throw new Frame.WireException(LumosErrors.ERR_FRAG_LENGTH_MISMATCH,
                    "content overruns the declared total");
        }
        System.arraycopy(content, 0, openBuf, openFilled, content.length);
        openFilled += content.length;
        openNext = index + 1;
        openLastAt = nowMillis;

        if ((flags & LumosConstants.FRAG_FLAG_LAST_DEFAULT) != 0) {
            return complete(false, expiredNow);
        }
        return new Offer(Outcome.ACCEPTED, null, false, expiredNow);
    }

    private Offer complete(final boolean restarted, final boolean expiredNow) throws Frame.WireException {
        if (openFilled != openTotal) {
            drop();
            throw new Frame.WireException(LumosErrors.ERR_FRAG_LENGTH_MISMATCH,
                    "LAST completed short of the declared total");
        }
        final byte[] payload = openBuf;
        completedValid = true;
        completedId = openId;
        drop();
        return new Offer(Outcome.COMPLETE, payload, restarted, expiredNow);
    }

    /** Discards the open assembly without recording a tombstone. */
    private void drop() {
        openActive = false;
        openBuf = null;
    }
}

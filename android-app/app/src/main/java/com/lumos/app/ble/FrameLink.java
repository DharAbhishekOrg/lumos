/*
 * Lumos - android-app/app / Frames over a fragmenting, serialized link.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TRN-002, TRS-TRN-003, TRS-PROTO-010
 * ADRs: ADR-002, ADR-004
 * Threading: serial-executor
 * Notes: The glue that makes S2a's layer carry real traffic: an outbound blob
 * is split at the negotiated MTU's budget and its fragments ride the
 * serialized GATT queue one write at a time (paced writes, TRS-TRN-002);
 * inbound fragments feed the reassembler with the caller's clock and completed
 * blobs surface whole. The layer stays content-opaque end to end - this class
 * moves bytes and never parses a frame.
 *
 * One blob is in flight at a time, and the NEXT fragment is enqueued only when
 * the previous one succeeds. That chaining is load-bearing twice over: a
 * failed fragment stops the rest of a doomed blob instead of pouring it at a
 * receiver that must gap-reject it, and two concurrent sends can never
 * interleave their fragments on the wire - which the single-assembly receiver
 * (TRS-TRN-003's serialized link) would treat as a restart. Inbound rejects
 * are silent with their stable code counted: the sender's retry budget is the
 * recovery path (TRS Sec. 9.4).
 */

package com.lumos.app.ble;

import com.lumos.protocol.wire.Fragmenter;
import com.lumos.protocol.wire.FragmentReassembler;
import com.lumos.protocol.wire.Frame;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Sends and receives opaque blobs over one fragmenting link direction. */
public final class FrameLink {

    /** Issues one fragment write on the platform link. */
    public interface FragmentWriter {

        /**
         * Writes one fragment; completion arrives through the queue.
         *
         * @param fragment the fragment bytes
         * @param epoch the connection epoch the write belongs to
         */
        void write(byte[] fragment, long epoch);
    }

    /** The terminal outcome of one blob send. */
    public interface SendCallback {

        /**
         * @param status GattOperationQueue.STATUS_SUCCESS or the failing status
         */
        void onSent(int status);
    }

    private static final class PendingSend {
        private final List<byte[]> fragments;
        private final SendCallback callback;

        private PendingSend(final List<byte[]> fragments, final SendCallback callback) {
            this.fragments = fragments;
            this.callback = callback;
        }
    }

    private final GattOperationQueue queue;
    private final FragmentWriter writer;
    private final FragmentReassembler reassembler;
    private final Consumer<byte[]> onBlob;
    private final Map<String, Long> dropCounts = new HashMap<>();
    private final ArrayDeque<PendingSend> sends = new ArrayDeque<>();

    private boolean sendInFlight;
    private int assemblyId;

    /**
     * @param queue the serialized operation queue (TRS-TRN-003)
     * @param writer the platform write seam
     * @param reassembler the inbound reassembler, timeout per carrying link
     * @param onBlob where completed inbound blobs go
     */
    public FrameLink(final GattOperationQueue queue, final FragmentWriter writer,
            final FragmentReassembler reassembler, final Consumer<byte[]> onBlob) {
        this.queue = queue;
        this.writer = writer;
        this.reassembler = reassembler;
        this.onBlob = onBlob;
    }

    /**
     * Sends one blob: split at the MTU budget, fragments written in order, one
     * blob on the wire at a time.
     *
     * @param blob the bytes to carry; never parsed here
     * @param negotiatedMtu the link's negotiated ATT MTU
     * @param callback the terminal outcome: success after the last fragment,
     *     or the first terminal failure, exactly once
     * @throws Frame.WireException when the blob cannot be split for this link
     */
    public void send(final byte[] blob, final int negotiatedMtu, final SendCallback callback)
            throws Frame.WireException {
        final List<byte[]> fragments =
                Fragmenter.split(blob, nextAssemblyId(), Fragmenter.payloadCap(negotiatedMtu));
        sends.addLast(new PendingSend(fragments, callback));
        dispatchNextSend();
    }

    /**
     * Feeds one received fragment.
     *
     * @param fragment the fragment bytes as received
     * @param nowMillis the caller's clock
     */
    public void onFragmentReceived(final byte[] fragment, final long nowMillis) {
        final FragmentReassembler.Offer offer;
        try {
            offer = reassembler.offer(fragment, nowMillis);
        } catch (final Frame.WireException rejected) {
            // Silent by design: the sender's retry budget recovers, and the
            // count is how tests and diagnostics see the path run.
            dropCounts.merge(rejected.code(), 1L, Long::sum);
            return;
        }
        if (offer.outcome() == FragmentReassembler.Outcome.COMPLETE) {
            onBlob.accept(offer.payload());
        }
    }

    /**
     * How many inbound fragments were rejected under a stable code.
     *
     * @param code the stable error code
     * @return the rejection count for that code
     */
    public long drops(final String code) {
        return dropCounts.getOrDefault(code, 0L);
    }

    private void dispatchNextSend() {
        if (sendInFlight || sends.isEmpty()) {
            return;
        }
        sendInFlight = true;
        final PendingSend send = sends.removeFirst();
        enqueueFragment(send, 0);
    }

    private void enqueueFragment(final PendingSend send, final int index) {
        final byte[] fragment = send.fragments.get(index);
        final boolean last = index == send.fragments.size() - 1;
        queue.enqueue(new GattOperationQueue.Operation() {
            @Override
            public void execute(final long epoch) {
                writer.write(fragment, epoch);
            }

            @Override
            public void onResult(final int status) {
                if (status != GattOperationQueue.STATUS_SUCCESS || last) {
                    sendInFlight = false;
                    send.callback.onSent(status);
                    dispatchNextSend();
                    return;
                }
                enqueueFragment(send, index + 1);
            }
        });
    }

    private int nextAssemblyId() {
        final int id = assemblyId;
        assemblyId = (assemblyId + 1) & 0xFF;
        return id;
    }
}

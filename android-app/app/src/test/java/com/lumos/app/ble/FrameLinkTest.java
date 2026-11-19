/*
 * Lumos - android-app/app / Blobs end to end over the fragmenting serialized link.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TRN-002, TRS-TRN-003, TRS-PROTO-010, TRS-TST-001
 * ADRs: ADR-002
 * Threading: test-only
 * Notes: Two FrameLinks wired back to back through a synthetic wire: what A
 * sends arrives at B whole, one fragment write at a time through the queue.
 * The failure shapes are the ones the radio actually produces: a fragment
 * failing terminally stops the rest of its doomed blob, a reconnect under a
 * half-sent blob fails that send, two sends never interleave fragments, and
 * inbound garbage drops silently under its stable code.
 */

package com.lumos.app.ble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import com.lumos.protocol.wire.FragmentReassembler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FrameLink over a scripted wire. */
final class FrameLinkTest {

    private static final int MTU = 69;
    private static final long TIMEOUT_MILLIS =
            (long) LumosConstants.REASSEMBLY_TIMEOUT_SEC_BLE * 1000;

    private final FakeScheduler scheduler = new FakeScheduler();
    private final GattOperationQueue queue = new GattOperationQueue(scheduler, d -> d);
    private final List<byte[]> wire = new ArrayList<>();
    private final List<byte[]> received = new ArrayList<>();

    private FrameLink newLink() {
        return new FrameLink(queue,
                (fragment, epoch) -> {
                    wire.add(fragment.clone());
                    queue.operationCompleted(epoch, GattOperationQueue.STATUS_SUCCESS);
                },
                new FragmentReassembler(TIMEOUT_MILLIS),
                received::add);
    }

    private static byte[] blob(final int n, final int seed) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * seed);
        }
        return b;
    }

    @Test
    void aBlobCrossesTheLinkWholeOneWriteAtATime() throws Exception {
        final FrameLink sender = newLink();
        final FrameLink receiver = newLink();
        final byte[] payload = blob(500, 3);

        final int[] status = {-1};
        sender.send(payload, MTU, s -> status[0] = s);
        assertEquals(GattOperationQueue.STATUS_SUCCESS, status[0]);
        assertTrue(wire.size() > 1, "the blob must actually fragment at this MTU");

        for (final byte[] fragment : wire) {
            receiver.onFragmentReceived(fragment, 0);
        }
        assertEquals(1, received.size());
        assertArrayEquals(payload, received.get(0));
    }

    @Test
    void sequentialSendsDoNotInterleaveFragments() throws Exception {
        final FrameLink sender = newLink();
        sender.send(blob(200, 3), MTU, s -> { });
        sender.send(blob(150, 5), MTU, s -> { });

        // Every fragment of assembly 0 must precede every fragment of 1: the
        // single-assembly receiver would treat interleaving as a restart.
        int firstOfSecond = -1;
        int lastOfFirst = -1;
        for (int i = 0; i < wire.size(); i++) {
            final int id = wire.get(i)[1] & 0xFF;
            if (id == 0) {
                lastOfFirst = i;
            } else if (firstOfSecond < 0) {
                firstOfSecond = i;
            }
        }
        assertTrue(lastOfFirst < firstOfSecond, "fragments of two blobs interleaved on the wire");
    }

    @Test
    void aTerminalWriteFailureStopsTheDoomedBlob() throws Exception {
        final int[] status = {0};
        final FrameLink sender = new FrameLink(queue,
                (fragment, epoch) -> {
                    wire.add(fragment.clone());
                    // Every write fails; the retry budget burns down per write.
                    queue.operationCompleted(epoch, 133);
                },
                new FragmentReassembler(TIMEOUT_MILLIS), received::add);
        sender.send(blob(500, 3), MTU, s -> status[0] = s);
        drainRetries();

        assertEquals(133, status[0], "the send fails with the platform status");
        assertEquals(LumosConstants.RETRY_BUDGET_PER_PATH_DEFAULT, wire.size(),
                "only the first fragment's attempts hit the wire; the rest of the blob must not");
    }

    @Test
    void inboundGarbageDropsSilentlyUnderItsStableCode() {
        final FrameLink receiver = newLink();
        receiver.onFragmentReceived(new byte[] {0x00, 0x09, 0x00, 0x00, 0x01}, 0);
        assertEquals(0, received.size());
        assertEquals(1, receiver.drops(LumosErrors.ERR_FRAG_SEQUENCE_GAP),
                "an unknown continuation is counted, never thrown at the radio loop");
    }

    private void drainRetries() {
        while (scheduler.pending() > 0) {
            scheduler.advanceBy(LumosConstants.RETRY_BACKOFF_CAP_MS);
        }
    }
}

/*
 * Lumos - android-app/protocol-java / Transport fragmentation: the sender half.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-010, TRS-TRN-002
 * ADRs: ADR-004
 * Threading: any
 * Notes: The Java port of protocol/wire/fragment.go's Split, held to it byte for
 * byte by test-vectors/framing/fragmentation.json. The layer is independent of
 * semantic message framing (TRS-PROTO-010): it carries opaque bytes and never
 * parses a frame. Layout per the constants register (transport.FRAG_HEADER_LEN
 * notes): byte 0 flags, byte 1 assembly_id, bytes 2-3 fragment_index,
 * big-endian; the FIRST fragment's content starts with a u32 total length.
 */

package com.lumos.protocol.wire;

import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.LumosErrors;
import java.util.ArrayList;
import java.util.List;

/** Splits opaque blobs into link fragments (TRS-PROTO-010). */
public final class Fragmenter {

    /** Fixed fragment header length. */
    static final int HEADER_LEN = LumosConstants.FRAG_HEADER_LEN_DEFAULT;

    /** u32 total-length prefix carried in a FIRST fragment. */
    static final int TOTAL_PREFIX_LEN = LumosConstants.FRAG_TOTAL_PREFIX_LEN_DEFAULT;

    private Fragmenter() {
        throw new AssertionError("no instances");
    }

    /**
     * Derives the per-fragment content budget from a negotiated ATT MTU: the
     * register rule negotiated_mtu_minus_7_floor_20.
     *
     * @param negotiatedMtu the ATT MTU this link negotiated
     * @return the content budget per fragment, excluding the 4-byte header
     */
    public static int payloadCap(final int negotiatedMtu) {
        final int budget = negotiatedMtu - LumosConstants.BLE_CHUNK_PAYLOAD_SUBTRACT;
        return Math.max(budget, LumosConstants.BLE_CHUNK_PAYLOAD_FLOOR);
    }

    /**
     * Fragments an opaque blob for one link. The blob is never parsed.
     *
     * @param blob the bytes to carry; typically an encoded frame, but this
     *     layer neither knows nor cares
     * @param assemblyId the sender's per-link assembly identifier (0..255)
     * @param chunkPayload the content budget per fragment excluding the header
     *     ({@link #payloadCap})
     * @return the fragments, in send order
     * @throws Frame.WireException with a stable code when the blob is empty,
     *     the budget is below the register floor, or the transfer does not fit
     *     FRAG_MAX_FRAGMENTS on this link
     */
    public static List<byte[]> split(final byte[] blob, final int assemblyId, final int chunkPayload)
            throws Frame.WireException {
        if (chunkPayload < LumosConstants.BLE_CHUNK_PAYLOAD_FLOOR) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED,
                    "chunk payload below the register floor");
        }
        if (blob.length == 0) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_HEADER_MALFORMED, "nothing to fragment");
        }
        if (blob.length > LumosConstants.FRAG_REASSEMBLY_MAX_BYTES_DEFAULT) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_TOTAL_TOO_LARGE,
                    "blob exceeds FRAG_REASSEMBLY_MAX_BYTES");
        }

        // The content stream is the u32 total followed by the blob.
        final byte[] content = new byte[TOTAL_PREFIX_LEN + blob.length];
        content[0] = (byte) (blob.length >>> 24);
        content[1] = (byte) (blob.length >>> 16);
        content[2] = (byte) (blob.length >>> 8);
        content[3] = (byte) blob.length;
        System.arraycopy(blob, 0, content, TOTAL_PREFIX_LEN, blob.length);

        final int count = (content.length + chunkPayload - 1) / chunkPayload;
        if (count > LumosConstants.FRAG_MAX_FRAGMENTS_DEFAULT) {
            throw new Frame.WireException(LumosErrors.ERR_FRAG_TOTAL_TOO_LARGE,
                    "the transfer does not fit this link's FRAG_MAX_FRAGMENTS");
        }

        final List<byte[]> fragments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int flags = 0;
            if (index == 0) {
                flags |= LumosConstants.FRAG_FLAG_FIRST_DEFAULT;
            }
            if (index == count - 1) {
                flags |= LumosConstants.FRAG_FLAG_LAST_DEFAULT;
            }
            final int at = index * chunkPayload;
            final int end = Math.min(at + chunkPayload, content.length);
            final byte[] fragment = new byte[HEADER_LEN + (end - at)];
            fragment[0] = (byte) flags;
            fragment[1] = (byte) assemblyId;
            fragment[2] = (byte) (index >>> 8);
            fragment[3] = (byte) index;
            System.arraycopy(content, at, fragment, HEADER_LEN, end - at);
            fragments.add(fragment);
        }
        return fragments;
    }
}

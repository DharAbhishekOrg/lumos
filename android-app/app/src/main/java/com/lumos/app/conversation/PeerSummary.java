/*
 * Lumos - android-app/app / Who a conversation is with, as the screen shows them.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-018, SRS-UX-002
 * ADRs: -
 * Threading: any
 * Notes: The match does not yet store the peer's name: profile previews
 * arrive with the Nearby-to-match slice (CS4). Until then displayName is
 * empty and the screen says "Your match", never a made-up name. Reach is
 * described as an outcome, not a transport (SRS-UX-002): here now, or not
 * nearby with messages still reaching them over the internet, or not nearby
 * with messages waiting until they meet.
 */

package com.lumos.app.conversation;

/**
 * The peer.
 *
 * @param displayName the name from their profile preview; empty until previews are stored (CS4)
 * @param reach how a message reaches them right now
 */
public record PeerSummary(String displayName, Reach reach) {

    /** How a message reaches the peer. */
    public enum Reach {
        /** Near each other now. */
        HERE_NOW,
        /** Not nearby; messages still reach them over the internet. */
        REMOTE,
        /** Not nearby and no internet path is configured; messages wait until they meet. */
        WAITS_UNTIL_NEARBY
    }

    /**
     * @param displayName see the record
     * @param reach see the record
     */
    public PeerSummary {
        displayName = displayName == null ? "" : displayName.strip();
    }
}

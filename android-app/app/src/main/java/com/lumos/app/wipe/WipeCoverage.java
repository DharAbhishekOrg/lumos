/*
 * Lumos - android-app/app / What wipe reaches, and what it does not.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-SAF-010, SRS-NFR-PRI-012
 * ADRs: ADR-003
 * Threading: any
 * Notes: SRS-SAF-010 ends with "to the documented platform limit", and threat
 * model Sec. 7.3 turns that into an obligation: "What cannot be reached is
 * named in the UI rather than implied to be gone." This class is that naming,
 * in a form a screen can render and a test can assert.
 *
 * Two kinds of gap, and they are different in kind, so they are separate lists:
 *
 * - NOT YET BUILT. Conversations, cached objects, queued packets and venue
 *   tokens have no store in WP-02. Wipe does not miss them; there is nothing
 *   there. Each names the work package that will add it, and the WP-02 closeout
 *   is where the list has to be empty.
 * - CANNOT BE REACHED. Deleted rows may persist in SQLite free pages and in
 *   flash blocks the filesystem has released but the controller has not erased
 *   (threat model LT-14). No application can fix that on either platform. What
 *   makes it survivable is the order: those bytes are ciphertext, and the key
 *   was destroyed in the phase before, so what remains is unreadable rather
 *   than merely deleted.
 *
 * Saying the second one out loud costs nothing and is the difference between a
 * claim that is true and a claim that would be found false by the first person
 * who ran a forensic tool.
 */

package com.lumos.app.wipe;

import java.util.Collections;
import java.util.List;

/** The documented limit of what emergency wipe covers on this build. */
public final class WipeCoverage {

    /** One thing SRS-SAF-010 names, and this build's honest status for it. */
    public static final class Item {

        private final String what;
        private final String status;

        private Item(final String what, final String status) {
            this.what = what;
            this.status = status;
        }

        /**
         * The data SRS-SAF-010 names.
         *
         * @return its description
         */
        public String what() {
            return what;
        }

        /**
         * What this build actually does about it.
         *
         * @return the status
         */
        public String status() {
            return status;
        }
    }

    private final List<Item> covered;
    private final List<Item> notYetBuilt;
    private final List<Item> beyondReach;

    private WipeCoverage(final List<Item> covered, final List<Item> notYetBuilt,
            final List<Item> beyondReach) {
        this.covered = Collections.unmodifiableList(covered);
        this.notYetBuilt = Collections.unmodifiableList(notYetBuilt);
        this.beyondReach = Collections.unmodifiableList(beyondReach);
    }

    /**
     * The coverage of the stores that exist after WP-02 slice S4c.
     *
     * @return the statement
     */
    public static WipeCoverage forWpTwo() {
        return new WipeCoverage(
                List.of(
                        new Item("app cryptographic state",
                                "the key-encryption key is destroyed first, so everything it "
                                        + "protected becomes unreadable at that moment"),
                        new Item("the local profile, its visibility policy and its media metadata",
                                "every row is deleted after the keys are gone"),
                        new Item("the block list",
                                "every row is deleted after the keys are gone"),
                        new Item("onboarding consent evidence and the Visible Nearby setting",
                                "every row is deleted after the keys are gone"),
                        new Item("local diagnostic events",
                                "every row is deleted after the keys are gone")),
                List.of(
                        new Item("conversations", "no message store exists yet - WP-05"),
                        new Item("cached objects", "no object cache exists yet - WP-05"),
                        new Item("queued packets", "no outbox exists yet - WP-03"),
                        new Item("venue tokens", "no venue store exists yet - WP-07")),
                List.of(
                        new Item("deleted rows in database free pages",
                                "they are ciphertext and the key is already destroyed; no "
                                        + "application can overwrite them on either platform"),
                        new Item("flash blocks the storage controller has not yet erased",
                                "the same: unreadable, not absent, and outside any app's "
                                        + "control")));
    }

    /**
     * What this build genuinely destroys.
     *
     * @return the covered items
     */
    public List<Item> covered() {
        return covered;
    }

    /**
     * What SRS-SAF-010 names that has no store in this build yet.
     *
     * @return the pending items, each naming its work package
     */
    public List<Item> notYetBuilt() {
        return notYetBuilt;
    }

    /**
     * What no application can reach, and why that is survivable here.
     *
     * @return the platform limits
     */
    public List<Item> beyondReach() {
        return beyondReach;
    }
}

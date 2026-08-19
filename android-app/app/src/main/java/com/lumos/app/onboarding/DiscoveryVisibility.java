/*
 * Lumos - android-app/app / Visible Nearby state, and the rule that it is off.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-008, SRS-DAT-002
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: SRS-ONB-008 is a P0 and threat model LT-33 is High: Visible Nearby is
 * never enabled by installation, update, reboot or permission grant. The
 * broadcast itself is WP-03; what WP-02 owns is the persisted state it will
 * read, and getting the DEFAULT right is the whole requirement.
 *
 * So the design is a default, not a code path. Absence of the record reads as
 * off. There is no "initialise visibility" step that could initialise it wrong,
 * no boolean parameter that could be passed true, and exactly one method that
 * can turn it on - named so its call sites are greppable and pinned by
 * DiscoveryVisibilityContractTest, which fails if a second one appears.
 *
 * That is also why none of the four SRS-ONB-008 triggers has a method here.
 * Installation, update, reboot and permission grant do not "leave visibility
 * alone" as an implemented behaviour; they have no entry point at all, which is
 * a stronger guarantee than any handler could be.
 */

package com.lumos.app.onboarding;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Whether the user has turned Visible Nearby on, persisted across restarts. */
public final class DiscoveryVisibility {

    /** The single-row identifier for the visibility state. */
    public static final String VISIBILITY_ID = "self";

    /** Payload format version; an unknown one fails closed, which means off. */
    private static final String VERSION_LINE = "lumos-visibility/1";

    /** The payload written when the user turns visibility on. */
    private static final String STATE_ON = "on";

    /** The payload written when the user turns visibility off. */
    private static final String STATE_OFF = "off";

    private final LocalStore store;

    /**
     * Binds the state to a store.
     *
     * @param store the opened local store
     */
    public DiscoveryVisibility(final LocalStore store) {
        this.store = store;
    }

    /**
     * Whether the user is currently discoverable by nearby devices.
     *
     * <p>False unless a stored record explicitly says on. A missing record, an
     * unparseable one and a record from a format this build does not know all
     * read as off - the failure direction that cannot expose anybody.
     *
     * @return true only when the user turned it on and has not turned it off
     * @throws StoreException on engine failure
     */
    public boolean isVisibleNearby() throws StoreException {
        final Optional<byte[]> raw = store.get(RecordKind.DISCOVERY_VISIBILITY, VISIBILITY_ID);
        if (raw.isEmpty()) {
            return false;
        }
        final String[] lines = new String(raw.get(), StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length < 2 || !VERSION_LINE.equals(lines[0])) {
            return false;
        }
        return STATE_ON.equals(lines[1]);
    }

    /**
     * Turns Visible Nearby on. The only method in the application that can.
     *
     * <p>The name is long because the name is the contract: SRS-ONB-008 permits
     * this state to change only in response to a person deciding it should, so
     * a call site that reads oddly is a call site that is wrong.
     *
     * @param atUtcMillis when the user asked, for the settings screen
     * @throws StoreException on low storage or engine failure
     */
    public void enableAfterExplicitUserRequest(final long atUtcMillis) throws StoreException {
        write(STATE_ON, atUtcMillis);
    }

    /**
     * Turns Visible Nearby off.
     *
     * <p>Unlike enabling, this needs no ceremony: turning a broadcast off is
     * always safe, and anything that can make a user less exposed should be as
     * easy to call as possible.
     *
     * @param atUtcMillis when it was turned off
     * @throws StoreException on low storage or engine failure
     */
    public void disable(final long atUtcMillis) throws StoreException {
        write(STATE_OFF, atUtcMillis);
    }

    /**
     * Writes the state record.
     *
     * @param state on or off
     * @param atUtcMillis the moment of the change
     * @throws StoreException on low storage or engine failure
     */
    private void write(final String state, final long atUtcMillis) throws StoreException {
        final String payload = VERSION_LINE + "\n" + state + "\n" + atUtcMillis + "\n";
        store.put(RecordKind.DISCOVERY_VISIBILITY, VISIBILITY_ID,
                payload.getBytes(StandardCharsets.UTF_8), null);
    }
}

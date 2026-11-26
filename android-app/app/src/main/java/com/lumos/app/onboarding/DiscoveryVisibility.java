/*
 * Lumos - android-app/app / Visible Nearby: off by default, on only for a chosen while.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-ONB-008, SRS-NBY-001, SRS-NBY-002, SRS-NBY-003, SRS-DAT-002
 * ADRs: ADR-002
 * Threading: serial-executor
 * Notes: SRS-ONB-008 is a P0 and threat model LT-33 is High: Visible Nearby is
 * never enabled by installation, update, reboot or permission grant. The
 * design is a default, not a code path: absence of the record reads as off,
 * there is no "initialise visibility" step, and exactly one method can turn it
 * on - named so its call sites are greppable, pinned by NoAutoVisibilityTest.
 * None of the four triggers has a method here; they have no entry point at
 * all, which is stronger than any handler could be. A record from an earlier
 * payload version also reads as OFF: an app update is one of the four
 * triggers, so visibility never carries across a format change without the
 * person choosing again.
 *
 * WP-03 S5 adds what SRS-NBY-001/002 require of the choice itself: enabling
 * means choosing a duration - the register default session, a custom duration
 * inside the register bounds, or until venue exit - and the state EXPIRES at
 * the chosen time by evaluation, not by timer: isVisibleNearby(now) is false
 * the millisecond the window ends, nothing ever rewrites the record to extend
 * it (no silent renew), and the indicator the UI renders (SRS-NBY-003) comes
 * from the same single evaluation so the person and the radio can never
 * disagree about whether they are visible.
 */

package com.lumos.app.onboarding;

import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Whether, and until when, the user chose to be discoverable nearby. */
public final class DiscoveryVisibility {

    /** The single-row identifier for the visibility state. */
    public static final String VISIBILITY_ID = "self";

    /** Payload format version; any other version fails closed, which means off. */
    private static final String VERSION_LINE = "lumos-visibility/2";

    /** How a visibility session ends (SRS-NBY-001). */
    public enum Mode {
        /** The register default session length. */
        DEFAULT_SESSION("default"),
        /** A user-chosen duration inside the register bounds. */
        CUSTOM("custom"),
        /** Until the venue-exit event; still never self-renewing. */
        UNTIL_VENUE_EXIT("venue");

        private final String token;

        Mode(final String token) {
            this.token = token;
        }

        private static Mode fromToken(final String token) {
            for (final Mode mode : values()) {
                if (mode.token.equals(token)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** The user's duration choice (SRS-NBY-001). */
    public static final class Selection {

        private final Mode mode;
        private final int durationSeconds;

        private Selection(final Mode mode, final int durationSeconds) {
            this.mode = mode;
            this.durationSeconds = durationSeconds;
        }

        /** @return the register default session */
        public static Selection defaultSession() {
            return new Selection(Mode.DEFAULT_SESSION,
                    LumosConstants.VISIBLE_NEARBY_DURATION_SEC_DEFAULT);
        }

        /**
         * @return the chosen window in seconds; 0 for until venue exit
         */
        public int durationSeconds() {
            return durationSeconds;
        }

        /**
         * @return the longest custom window, from the register (8 hours)
         */
        public static int maximumSeconds() {
            return LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MAX;
        }

        /**
         * @return the shortest custom window, from the register (15 minutes)
         */
        public static int minimumSeconds() {
            return LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MIN;
        }

        /**
         * A custom duration, held to the register bounds.
         *
         * @param seconds the chosen duration
         * @return the selection
         * @throws IllegalArgumentException outside the register bounds - a
         *     surface offering a value the register forbids is a defect, not
         *     an input to accommodate
         */
        public static Selection customDuration(final int seconds) {
            if (seconds < LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MIN
                    || seconds > LumosConstants.VISIBLE_NEARBY_DURATION_SEC_MAX) {
                throw new IllegalArgumentException("custom duration outside the register bounds");
            }
            return new Selection(Mode.CUSTOM, seconds);
        }

        /** @return visibility until the venue-exit event */
        public static Selection untilVenueExit() {
            return new Selection(Mode.UNTIL_VENUE_EXIT, 0);
        }
    }

    /** What the persistent indicator shows (SRS-NBY-003). */
    public static final class Indicator {

        private final boolean visible;
        private final Mode mode;
        private final long expiresAtMillis;

        private Indicator(final boolean visible, final Mode mode, final long expiresAtMillis) {
            this.visible = visible;
            this.mode = mode;
            this.expiresAtMillis = expiresAtMillis;
        }

        /** @return whether the person is discoverable right now */
        public boolean visible() {
            return visible;
        }

        /** @return the active mode, or null when not visible */
        public Mode mode() {
            return mode;
        }

        /** @return when visibility ends, or 0 for until-venue-exit and off */
        public long expiresAtMillis() {
            return expiresAtMillis;
        }
    }

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
     * <p>False unless a stored record explicitly says so AND its window has
     * not ended. A missing record, an unparseable one, a record from another
     * format version and an expired window all read as off - every failure
     * direction is the one that cannot expose anybody. Expiry is evaluation:
     * nothing rewrites the record, so nothing can silently renew it
     * (SRS-NBY-002).
     *
     * @param nowMillis the caller's clock
     * @return true only inside a window the user explicitly chose
     * @throws StoreException on engine failure
     */
    public boolean isVisibleNearby(final long nowMillis) throws StoreException {
        return indicator(nowMillis).visible();
    }

    /**
     * The persistent-indicator state (SRS-NBY-003): the single evaluation the
     * UI, the advertising controller and the settings screen all share.
     *
     * @param nowMillis the caller's clock
     * @return what the indicator shows
     * @throws StoreException on engine failure
     */
    public Indicator indicator(final long nowMillis) throws StoreException {
        final Optional<byte[]> raw = store.get(RecordKind.DISCOVERY_VISIBILITY, VISIBILITY_ID);
        if (raw.isEmpty()) {
            return new Indicator(false, null, 0);
        }
        final String[] lines = new String(raw.get(), StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length < 4 || !VERSION_LINE.equals(lines[0])) {
            return new Indicator(false, null, 0);
        }
        final Mode mode = Mode.fromToken(lines[1]);
        if (mode == null || !"on".equals(lines[2])) {
            return new Indicator(false, null, 0);
        }
        if (mode == Mode.UNTIL_VENUE_EXIT) {
            return new Indicator(true, mode, 0);
        }
        final long expiresAt;
        try {
            expiresAt = Long.parseLong(lines[3]);
        } catch (final NumberFormatException unreadable) {
            return new Indicator(false, null, 0);
        }
        if (nowMillis >= expiresAt) {
            // SRS-NBY-002: the window ended, so visibility ended - by
            // evaluation, with nothing to renew and nothing having run.
            return new Indicator(false, null, 0);
        }
        return new Indicator(true, mode, expiresAt);
    }

    /**
     * Turns Visible Nearby on for the chosen window. The only method in the
     * application that can.
     *
     * <p>The name is long because the name is the contract: SRS-ONB-008
     * permits this state to change only in response to a person deciding it
     * should, so a call site that reads oddly is a call site that is wrong.
     *
     * @param selection the duration the user chose (SRS-NBY-001)
     * @param nowMillis when the user asked
     * @throws StoreException on low storage or engine failure
     */
    public void enableAfterExplicitUserRequest(final Selection selection, final long nowMillis)
            throws StoreException {
        final long expiresAt = selection.mode == Mode.UNTIL_VENUE_EXIT
                ? 0
                : nowMillis + 1000L * selection.durationSeconds;
        write(selection.mode, "on", expiresAt, nowMillis);
    }

    /**
     * Turns Visible Nearby off.
     *
     * <p>Unlike enabling, this needs no ceremony: anything that can make a
     * user less exposed should be as easy to call as possible.
     *
     * @param nowMillis when it was turned off
     * @throws StoreException on low storage or engine failure
     */
    public void disable(final long nowMillis) throws StoreException {
        write(Mode.DEFAULT_SESSION, "off", 0, nowMillis);
    }

    /**
     * Ends an until-venue-exit session because the venue was left. Only turns
     * visibility OFF - the safe direction - and only when the active mode is
     * until-venue-exit, so a stray venue event cannot end a timed session the
     * user still wanted.
     *
     * @param nowMillis when the venue exit happened
     * @throws StoreException on low storage or engine failure
     */
    public void endOnVenueExit(final long nowMillis) throws StoreException {
        final Indicator current = indicator(nowMillis);
        if (current.visible() && current.mode() == Mode.UNTIL_VENUE_EXIT) {
            disable(nowMillis);
        }
    }

    private void write(final Mode mode, final String state, final long expiresAt,
            final long atUtcMillis) throws StoreException {
        final String payload = VERSION_LINE + "\n" + mode.token + "\n" + state + "\n"
                + expiresAt + "\n" + atUtcMillis + "\n";
        store.put(RecordKind.DISCOVERY_VISIBILITY, VISIBILITY_ID,
                payload.getBytes(StandardCharsets.UTF_8), null);
    }
}

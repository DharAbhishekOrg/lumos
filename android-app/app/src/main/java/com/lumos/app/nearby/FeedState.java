/*
 * Lumos - android-app/app / What the Nearby screen shows, as a value.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-010, SRS-NBY-014, TRS-AND-005
 * ADRs: ADR-002
 * Threading: any
 * Notes: The permission/adapter matrix SRS-NBY-010 names, folded to the four
 * situations a user can actually act on - radio off, permission needed,
 * radio unavailable, radio busy - plus the two live ones: scanning with
 * nothing yet (the skeleton state; SRS-NBY-014's answer to indefinite
 * spinners is that EMPTY is a first-class state, not a spinner that never
 * resolves) and cards. The mapping from the TRS-AND-005 typed failures
 * lives here so every surface folds the OEM long tail the same way, and the
 * state is a value so the UI renders it without owning any radio logic.
 */

package com.lumos.app.nearby;

import com.lumos.app.ble.BlePermissions;
import com.lumos.app.ble.PresenceTable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One renderable state of the Nearby feed. */
public final class FeedState {

    /** The situations the feed distinguishes for the user. */
    public enum Kind {
        /** Bluetooth is off or the adapter is gone: the user can turn it on. */
        RADIO_OFF,
        /** A runtime permission is missing: the user can grant it. */
        PERMISSION_NEEDED,
        /** The stack refused in a way the user cannot fix here. */
        RADIO_UNAVAILABLE,
        /** The stack is temporarily saturated or throttling; it passes. */
        RADIO_BUSY,
        /** Scanning runs and nothing has authenticated yet: skeletons. */
        SCANNING_EMPTY,
        /** Authenticated, filtered presence cards. */
        CARDS,
        /** Live discovery is not wired in this build yet (NB-1): nothing is sent or looked for. */
        NOT_YET
    }

    private final Kind kind;
    private final List<PresenceTable.Card> cards;

    private FeedState(final Kind kind, final List<PresenceTable.Card> cards) {
        this.kind = kind;
        this.cards = cards;
    }

    /**
     * A radio-trouble state, folded from the typed scan failure.
     *
     * @param failure the TRS-AND-005 typed state
     * @return the feed state the user should see
     */
    public static FeedState of(final BlePermissions.ScanFailure failure) {
        Objects.requireNonNull(failure, "failure");
        switch (failure) {
            case ADAPTER_UNAVAILABLE:
                return new FeedState(Kind.RADIO_OFF, List.of());
            case PERMISSION_MISSING:
                return new FeedState(Kind.PERMISSION_NEEDED, List.of());
            case ALREADY_STARTED:
            case OUT_OF_HARDWARE_RESOURCES:
            case SCANNING_TOO_FREQUENTLY:
                return new FeedState(Kind.RADIO_BUSY, List.of());
            case APPLICATION_REGISTRATION_FAILED:
            case FEATURE_UNSUPPORTED:
            case INTERNAL_ERROR:
            case OEM_UNDOCUMENTED:
            default:
                return new FeedState(Kind.RADIO_UNAVAILABLE, List.of());
        }
    }

    /**
     * The scanning-with-no-cards state.
     *
     * @return the skeleton state
     */
    /**
     * Live discovery is not wired in this build yet (NB-1).
     *
     * @return the state that says so
     */
    public static FeedState notYet() {
        return new FeedState(Kind.NOT_YET, List.of());
    }

    public static FeedState scanningEmpty() {
        return new FeedState(Kind.SCANNING_EMPTY, List.of());
    }

    /**
     * The cards state.
     *
     * @param cards the filtered, ordered presence cards
     * @return the cards state
     */
    public static FeedState cards(final List<PresenceTable.Card> cards) {
        return new FeedState(Kind.CARDS, List.copyOf(cards));
    }

    /** @return which situation this is */
    public Kind kind() {
        return kind;
    }

    /** @return the cards, empty except in CARDS */
    public List<PresenceTable.Card> cards() {
        return Collections.unmodifiableList(cards);
    }
}

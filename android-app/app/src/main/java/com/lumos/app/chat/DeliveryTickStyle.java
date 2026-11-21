/*
 * Lumos - android-app/app / How each delivery state looks and sounds.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-005, SRS-RMT-013
 * ADRs: ADR-002
 * Threading: main-safe
 * Notes: The owner's WhatsApp-style ruling (2026-11-21) as a table: the
 * glyph comes from the vector-pinned tick mapping (so Android and iOS draw
 * the same tick for the same truth), the tint from the ruling (grey, blue
 * for read, red for failed), and the spoken label from the STATE - queued
 * and sending share a clock but say different things. Delivered and read
 * share the double tick and differ in tint and in words, so the distinction
 * never rests on colour alone. Pure ints and enums: the mapping is JVM-tested
 * without a device, and apply() is the one line that touches a View.
 */

package com.lumos.app.chat;

import android.content.res.ColorStateList;
import android.widget.ImageView;
import com.lumos.app.R;
import com.lumos.protocol.chat.DeliveryMachine;

/** Maps a delivery state to its tick glyph, tint and spoken label. */
public final class DeliveryTickStyle {

    /**
     * One state's presentation.
     *
     * @param drawableRes the tick glyph
     * @param colorRes the tint
     * @param labelRes the spoken label
     */
    public record Style(int drawableRes, int colorRes, int labelRes) { }

    private DeliveryTickStyle() {
        throw new AssertionError("no instances");
    }

    /**
     * The presentation of a state.
     *
     * @param state the message's delivery state
     * @return its glyph, tint and label
     */
    public static Style of(final DeliveryMachine.State state) {
        return new Style(drawableFor(DeliveryMachine.tickOf(state)),
                colorFor(DeliveryMachine.tickOf(state)), labelFor(state));
    }

    /**
     * Renders a state onto a tick view.
     *
     * @param view the tick's ImageView
     * @param state the message's delivery state
     */
    public static void apply(final ImageView view, final DeliveryMachine.State state) {
        final Style style = of(state);
        view.setImageResource(style.drawableRes());
        view.setImageTintList(ColorStateList.valueOf(
                view.getContext().getColor(style.colorRes())));
        view.setContentDescription(view.getContext().getString(style.labelRes()));
    }

    static int drawableFor(final DeliveryMachine.Tick tick) {
        switch (tick) {
            case SINGLE:
                return R.drawable.ic_tick_single;
            case DOUBLE:
            case DOUBLE_READ:
                return R.drawable.ic_tick_double;
            case FAILED:
                return R.drawable.ic_tick_failed;
            case NEARBY:
                return R.drawable.ic_tick_nearby;
            case CLOCK:
            default:
                return R.drawable.ic_tick_clock;
        }
    }

    static int colorFor(final DeliveryMachine.Tick tick) {
        switch (tick) {
            case SINGLE:
            case DOUBLE:
                return R.color.tick_sent;
            case DOUBLE_READ:
                return R.color.tick_read;
            case FAILED:
                return R.color.tick_failed;
            case NEARBY:
                return R.color.tick_nearby;
            case CLOCK:
            default:
                return R.color.tick_pending;
        }
    }

    static int labelFor(final DeliveryMachine.State state) {
        switch (state) {
            case SENDING:
                return R.string.delivery_sending;
            case SENT:
                return R.string.delivery_sent;
            case DELIVERED:
                return R.string.delivery_delivered;
            case READ:
                return R.string.delivery_read;
            case FAILED:
                return R.string.delivery_failed;
            case LOCAL_ONLY_WAITING:
                return R.string.delivery_local_only_waiting;
            case QUEUED:
            default:
                return R.string.delivery_queued;
        }
    }
}

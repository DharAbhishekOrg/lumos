/*
 * Lumos - android-app/app / Swipe a message towards the middle to reply to it.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-UX-011, SRS-UX-007
 * ADRs: -
 * Threading: main
 * Notes: The reply gesture people know from other messengers (CR-009). A
 * bubble that offers Reply can be swiped towards the reading end (right in
 * left-to-right languages, left in right-to-left ones); the swipe starts the
 * reply and the bubble springs back, so nothing is ever removed by a swipe.
 * Day dividers and deleted messages do not move. The gesture is a shortcut
 * only: Reply is also in the long-press menu and in the accessibility
 * actions, so it never depends on a gesture (SRS-UX-007).
 */

package com.lumos.app.conversation;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import java.util.function.IntFunction;
import java.util.function.Consumer;

/** The swipe-to-reply gesture on the message list. */
final class SwipeToReply extends ItemTouchHelper.SimpleCallback {

    private final IntFunction<ConversationRows.Bubble> bubbleAt;
    private final Consumer<ConversationRows.Bubble> reply;

    /**
     * @param bubbleAt the bubble at an adapter position, or null for any other row
     * @param reply starts a reply
     */
    SwipeToReply(final IntFunction<ConversationRows.Bubble> bubbleAt, final Consumer<ConversationRows.Bubble> reply) {
        super(0, ItemTouchHelper.END);
        this.bubbleAt = bubbleAt;
        this.reply = reply;
    }

    /**
     * Attaches the gesture.
     *
     * @param list the message list
     * @param bubbleAt see the constructor
     * @param reply see the constructor
     */
    static void attach(final RecyclerView list, final IntFunction<ConversationRows.Bubble> bubbleAt,
            final Consumer<ConversationRows.Bubble> reply) {
        new ItemTouchHelper(new SwipeToReply(bubbleAt, reply)).attachToRecyclerView(list);
    }

    @Override
    public int getSwipeDirs(@NonNull final RecyclerView list, @NonNull final RecyclerView.ViewHolder holder) {
        final ConversationRows.Bubble b = bubbleAt.apply(holder.getBindingAdapterPosition());
        return b != null && ConversationPresenter.actionsFor(b).contains(ConversationPresenter.Action.REPLY)
                ? super.getSwipeDirs(list, holder) : 0;
    }

    @Override
    public boolean onMove(@NonNull final RecyclerView list, @NonNull final RecyclerView.ViewHolder from,
            @NonNull final RecyclerView.ViewHolder to) {
        return false;
    }

    @Override
    public void onSwiped(@NonNull final RecyclerView.ViewHolder holder, final int direction) {
        final int at = holder.getBindingAdapterPosition();
        final ConversationRows.Bubble b = bubbleAt.apply(at);
        final RecyclerView.Adapter<?> adapter = holder.getBindingAdapter();
        if (adapter != null && at != RecyclerView.NO_POSITION) {
            adapter.notifyItemChanged(at); // spring back: a swipe never removes a message
        }
        if (b != null) {
            reply.accept(b);
        }
    }

    @Override
    public float getSwipeThreshold(@NonNull final RecyclerView.ViewHolder holder) {
        return SWIPE_THRESHOLD;
    }

    /** A third of the row's width starts a reply, so a scroll that drifts sideways does not. */
    private static final float SWIPE_THRESHOLD = 0.33f;
}

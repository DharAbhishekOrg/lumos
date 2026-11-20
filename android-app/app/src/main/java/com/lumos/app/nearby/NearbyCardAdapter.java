/*
 * Lumos - android-app/app / The card list, as thin as a list can be.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-NBY-008, SRS-NBY-012, SRS-NBY-015
 * ADRs: ADR-002
 * Threading: main
 * Notes: Every decision was made before this class: the controller chose and
 * ordered the cards, the copy is fixed in resources, and the hide action
 * only forwards to the host. The adapter binds what it is handed - a title,
 * a presence line, a hide button - and shows nothing derived from protocol
 * state (SRS-NBY-008: no identifier, no signal number, no distance reaches
 * a view).
 */

package com.lumos.app.nearby;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lumos.app.R;
import com.lumos.app.ble.PresenceTable;
import java.util.ArrayList;
import java.util.List;

/** Binds presence cards into the Nearby list. */
public final class NearbyCardAdapter extends RecyclerView.Adapter<NearbyCardAdapter.Holder> {

    /** One bound card view. */
    public static final class Holder extends RecyclerView.ViewHolder {
        Holder(final View view) {
            super(view);
        }
    }

    private final NearbyHost host;
    private final List<PresenceTable.Card> cards = new ArrayList<>();

    /**
     * Binds the adapter to its host.
     *
     * @param host the running app, for the hide action
     */
    public NearbyCardAdapter(final NearbyHost host) {
        this.host = host;
    }

    /**
     * Replaces the shown cards with the controller's latest decision.
     *
     * @param latest the filtered, ordered cards
     */
    @SuppressWarnings("NotifyDataSetChanged")
    public void submit(final List<PresenceTable.Card> latest) {
        cards.clear();
        cards.addAll(latest);
        // A venue-sized list is at most a few hundred stable rows; full
        // rebinds stay well inside the SRS-NFR-PERF-009 budget the
        // responsiveness test holds the controller to, and diffing against
        // rotating presence would buy jank-free animations this screen does
        // not have yet. Revisit with WP-10's polish pass.
        notifyDataSetChanged();
    }

    @Override
    @NonNull
    public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_nearby_card, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder holder, final int position) {
        final PresenceTable.Card card = cards.get(position);
        holder.itemView.findViewById(R.id.card_hide)
                .setOnClickListener(view -> host.hideCard(card.peerKey()));
    }

    @Override
    public int getItemCount() {
        return cards.size();
    }
}

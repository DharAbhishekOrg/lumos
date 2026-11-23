/*
 * Lumos - android-app/app / The conversation screen's source, on the running chat stack.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-003, SRS-RMT-010, SRS-RMT-014, SRS-RMT-016, SRS-UX-012
 * ADRs: ADR-012
 * Threading: serial-executor
 * Notes: Runs on the app's relay executor, where the chat store lives. Every
 * failure is answered, never thrown at the screen: a store that cannot open
 * means the conversation does not open; a send that cannot seal is FAILED
 * and the composer keeps the text. After anything that queues an envelope
 * or a receipt it asks for a relay pass at once, so a message does not wait
 * for the periodic poll. Read receipts are on until the settings slice gives
 * the person the switch (SRS-RMT-014); delivery receipts never depend on it.
 * The peer's name is not stored yet (CS4), so displayName is empty. Ending
 * a conversation asks for a pass too: that pass revokes the peer's
 * capability at the relay.
 */

package com.lumos.app.conversation;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.lumos.app.BuildConfig;
import com.lumos.app.LumosApplication;
import com.lumos.app.chat.ChatRecords;
import com.lumos.app.relay.ChatRuntime;
import com.lumos.app.relay.ConversationSafety;
import com.lumos.app.relay.RelayException;
import com.lumos.app.relay.RelayJobs;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.wire.Frame;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;

/** The chat stack behind one conversation screen. */
public final class RuntimeConversationSource implements ConversationSource {

    /** Read receipts until the settings slice adds the switch (SRS-RMT-014). */
    static final boolean READ_RECEIPTS_DEFAULT = true;

    private final LumosApplication app;

    /** @param app the running app */
    public RuntimeConversationSource(final LumosApplication app) {
        this.app = app;
    }

    private Optional<ChatRuntime> runtime() {
        try {
            return Optional.ofNullable(app.runtime());
        } catch (final IOException | GeneralSecurityException | StoreException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean entryAllowed(final byte[] matchId) {
        try {
            final Optional<ChatRuntime> rt = runtime();
            return rt.isPresent() && rt.get().chat().peerIdentity(matchId).isPresent();
        } catch (final StoreException e) {
            return false;
        }
    }

    @Override
    public PeerSummary peer(final byte[] matchId) {
        final PeerSummary.Reach reach = BuildConfig.RELAY_BASE_URL.isEmpty()
                ? PeerSummary.Reach.WAITS_UNTIL_NEARBY : PeerSummary.Reach.REMOTE;
        return new PeerSummary("", reach);
    }

    @Override
    public List<ChatRecords.Message> messages(final byte[] matchId) {
        try {
            final Optional<ChatRuntime> rt = runtime();
            return rt.isPresent() ? rt.get().chat().messages(matchId) : List.of();
        } catch (final StoreException e) {
            return List.of();
        }
    }

    @Override
    public boolean online() {
        final ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        final Network n = cm == null ? null : cm.getActiveNetwork();
        final NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override
    public SendOutcome send(final byte[] matchId, final RemoteText.TextEvent event) {
        try {
            final Optional<ChatRuntime> rt = runtime();
            final Optional<byte[]> peer = rt.isPresent() ? rt.get().chat().peerIdentity(matchId) : Optional.empty();
            if (peer.isEmpty()) {
                return SendOutcome.FAILED;
            }
            final Optional<byte[]> id = rt.get().send(matchId, peer.get(), event);
            if (id.isEmpty()) {
                return SendOutcome.NOT_READY;
            }
            kick(app);
            return SendOutcome.QUEUED;
        } catch (final RelayException e) {
            return SendOutcome.NOT_READY;
        } catch (final StoreException | GeneralSecurityException | Frame.WireException e) {
            return SendOutcome.FAILED;
        }
    }

    @Override
    public boolean deleteForMe(final byte[] messageId) {
        try {
            final Optional<ChatRuntime> rt = runtime();
            return rt.isPresent() && rt.get().chat().deleteForMe(messageId);
        } catch (final StoreException e) {
            return false;
        }
    }

    @Override
    public void markRead(final byte[] matchId) {
        try {
            final Optional<ChatRuntime> rt = runtime();
            if (rt.isPresent() && rt.get().chat().markRead(matchId, READ_RECEIPTS_DEFAULT) > 0) {
                kick(app);
            }
        } catch (final StoreException e) {
            // Nothing was marked; the next open tries again.
        }
    }

    @Override
    public boolean end(final byte[] matchId, final ConversationSafety.Ending how) {
        try {
            final Optional<ChatRuntime> rt = runtime();
            if (rt.isEmpty() || !rt.get().safety().end(matchId, how)) {
                return false;
            }
            kick(app);
            return true;
        } catch (final StoreException e) {
            return false;
        }
    }

    private static void kick(final Context context) {
        if (!BuildConfig.RELAY_BASE_URL.isEmpty()) {
            RelayJobs.kick(context);
        }
    }
}

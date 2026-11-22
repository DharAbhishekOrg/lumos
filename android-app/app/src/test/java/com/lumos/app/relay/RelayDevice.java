/*
 * Lumos - android-app/app / A test device: identity, SQLite file, chat store and the relay parts, killable.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-TST-001, TRS-TST-006
 * ADRs: ADR-001, ADR-003
 * Threading: test-only
 * Notes: Everything a device holds in memory is built in boot() and nothing
 * else survives kill(): only the SQLite file and the data keys do, exactly
 * as after Android kills the process. Identities are real Ed25519 keys from
 * the JDK, so every signature the registry and X3DH check is real.
 */

package com.lumos.app.relay;

import com.lumos.app.Fixtures;
import com.lumos.app.chat.ChatStore;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.identity.JcaIdentityCrypto;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.chat.RemoteSession;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.List;

/** One device in a relay test. */
final class RelayDevice {

    private static final int SPKI_PREFIX = 12;
    private static final SecureRandom RNG = new SecureRandom();

    final byte[] edPub;
    private final KeyPair ed;
    private final byte[] dhPriv = new byte[32];
    private final Path file;
    private final DataKeyHierarchy hierarchy = DataKeyHierarchy.create(SoftwareKekStore.create());
    private final Fixtures.TestClock clock;
    private final HttpTransport transport;
    private final List<AutoCloseable> open;

    LocalStore store;
    ChatStore chat;
    RelayRoutes routes;
    RelayAccount account;
    PrekeyPublisher prekeys;
    RelaySync sync;
    RelaySessions sessions;

    RelayDevice(final Fixtures.TestClock clock, final HttpTransport transport, final List<AutoCloseable> open)
            throws Exception {
        this.clock = clock;
        this.transport = transport;
        this.open = open;
        ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final byte[] spki = ed.getPublic().getEncoded();
        edPub = Arrays.copyOfRange(spki, SPKI_PREFIX, spki.length);
        RNG.nextBytes(dhPriv);
        file = Files.createTempFile("lumos-relay", ".db");
        file.toFile().deleteOnExit();
        boot();
    }

    byte[] sign(final byte[] message) throws java.security.GeneralSecurityException {
        final Signature s = Signature.getInstance("Ed25519");
        s.initSign(ed.getPrivate());
        s.update(message);
        return s.sign();
    }

    private RemoteSession.Identity identity() throws Exception {
        final byte[] binding = sign(LumosCrypto.concat(
                LumosConstants.X3DH_IDENTITY_BINDING_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                LumosCrypto.x25519PublicFromPrivate(dhPriv)));
        return new RemoteSession.Identity(edPub, dhPriv, binding);
    }

    /** Starts the "process": every object fresh, over the same file and keys. */
    void boot() throws Exception {
        final JdbcSqlDatabase db = JdbcSqlDatabase.atPath(file.toString());
        open.add(db);
        store = LocalStore.open(db, hierarchy.deriveKey(DataKeyHierarchy.DataDomain.DATABASE), () -> Long.MAX_VALUE,
                clock);
        chat = ChatStore.over(store, identity(), clock, () -> {
            final byte[] b = new byte[32];
            RNG.nextBytes(b);
            return b;
        });
        routes = RelayRoutes.over(store, RNG);
        account = RelayAccount.over(store, new JcaIdentityCrypto());
        prekeys = PrekeyPublisher.over(store, chat, identity(), this::sign, RNG);
        final RelayClient anonymous = RelayClient.anonymous(transport);
        sync = RelaySync.over(chat, routes, account, prekeys, anonymous, clock);
        sessions = RelaySessions.over(chat, routes, account, anonymous, RNG);
    }

    /** Android kills the process; the next launch boots from disk. */
    void kill() throws Exception {
        store = null;
        chat = null;
        routes = null;
        account = null;
        prekeys = null;
        sync = null;
        sessions = null;
        boot();
    }

    byte[] routingId() throws Exception {
        return account.current().orElseThrow().routingId();
    }

    byte[] credential() throws Exception {
        return account.current().orElseThrow().credential();
    }
}

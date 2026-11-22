/*
 * Lumos - android-app/app / The assembled runtime, from onboarding's own identities to a delivered tick.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-RMT-001, SRS-RMT-005, SRS-RMT-007, SRS-RMT-018, TRS-TST-001
 * ADRs: ADR-001, ADR-003, ADR-008
 * Threading: test-only
 * Notes: The identities come from IdentityKeyGenerator - the path onboarding
 * runs - wrapped under a KEK, and ChatIdentity unwraps them. So this proves
 * the stored binding signature is the one X3DH verifies, that the unwrap
 * recovers usable keys, and that ChatRuntime wires every part to the same
 * store: a message sent with send() arrives, and the sender sees two ticks.
 */

package com.lumos.app.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lumos.app.Fixtures;
import com.lumos.app.data.DataKeyHierarchy;
import com.lumos.app.identity.ChatIdentity;
import com.lumos.app.identity.IdentityKeyGenerator;
import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.app.identity.JcaIdentityCrypto;
import com.lumos.app.keystore.SoftwareKekStore;
import com.lumos.app.store.JdbcSqlDatabase;
import com.lumos.app.store.LocalStore;
import com.lumos.protocol.TextEventKind;
import com.lumos.protocol.chat.DeliveryMachine;
import com.lumos.protocol.chat.RemoteText;
import com.lumos.protocol.match.MatchMachine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The chat runtime, assembled as the app assembles it. */
public class ChatRuntimeTest {

    private static final SecureRandom RNG = new SecureRandom();
    private final Fixtures.TestClock clock = new Fixtures.TestClock(1_700_000_000_000L);
    private final FakeRelay relay = new FakeRelay(clock);
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (final AutoCloseable c : open) {
            c.close();
        }
    }

    /** One installed app: KEK, data keys, database, onboarding's identity, and the runtime. */
    private final class App {
        private final IdentityKeyRecord record;
        private final ChatRuntime runtime;

        App() throws Exception {
            final SoftwareKekStore kek = SoftwareKekStore.create();
            final DataKeyHierarchy keys = DataKeyHierarchy.create(kek);
            final Path file = Files.createTempFile("lumos-runtime", ".db");
            file.toFile().deleteOnExit();
            final JdbcSqlDatabase db = JdbcSqlDatabase.atPath(file.toString());
            open.add(db);
            final LocalStore store = LocalStore.open(db, keys.deriveKey(DataKeyHierarchy.DataDomain.DATABASE),
                    () -> Long.MAX_VALUE, clock);
            record = IdentityKeyGenerator.generate(new JcaIdentityCrypto(), kek);
            final ChatIdentity identity = ChatIdentity.unlock(record, kek, new JcaIdentityCrypto());
            open.add(identity);
            runtime = ChatRuntime.assemble(store, identity, new JcaIdentityCrypto(), relay, clock, RNG);
        }
    }

    @Test
    void onboardingsIdentitiesChatThroughTheAssembledRuntime() throws Exception {
        final App asha = new App();
        final App ravi = new App();
        assertNull(asha.runtime.sync().run().stoppedBy());
        assertNull(ravi.runtime.sync().run().stoppedBy());
        final byte[] match = new byte[16];
        RNG.nextBytes(match);
        final MatchMachine.State matched = MatchMachine.State.MATCHED;
        asha.runtime.chat().acceptConversation(match, ravi.record.ed25519Public());
        ravi.runtime.chat().acceptConversation(match, asha.record.ed25519Public());
        assertFalse(asha.runtime.send(match, ravi.record.ed25519Public(),
                new RemoteText.TextEvent(TextEventKind.TEXT, null, "early".getBytes(StandardCharsets.UTF_8)))
                .isPresent(), "no route yet: nothing is queued and no session starts");
        ravi.runtime.exchange().take(match, matched, asha.runtime.exchange().offer(match, matched).orElseThrow());
        asha.runtime.exchange().take(match, matched, ravi.runtime.exchange().offer(match, matched).orElseThrow());
        asha.runtime.sync().run();
        ravi.runtime.sync().run();

        assertTrue(asha.runtime.send(match, ravi.record.ed25519Public(),
                new RemoteText.TextEvent(TextEventKind.TEXT, null, "hi".getBytes(StandardCharsets.UTF_8)))
                .isPresent(), "the session starts on demand from Ravi's published bundle");
        assertEquals(1, asha.runtime.sync().run().sent());
        assertEquals(1, ravi.runtime.sync().run().shown());
        ravi.runtime.sync().run();
        asha.runtime.sync().run();
        assertEquals(DeliveryMachine.State.DELIVERED, asha.runtime.chat().messages(match).get(0).state());
    }
}

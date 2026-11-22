/*
 * Lumos - android-app/app / This device's relay registration, made once and kept sealed.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-007, TRS-LOC-001, TRS-BCK-002
 * ADRs: ADR-001, ADR-003
 * Threading: serial-executor
 * Notes: Registration needs no phone number and no email (TRS-BCK-007): the
 * device makes a fresh Ed25519 key, signs the registry's challenge under the
 * DEVICE_AUTH_SIGNING_INFO domain, and gets back a routing id and a bearer
 * credential. The key is not the identity key, so the relay registration
 * cannot be linked to the identity by key reuse; it is used once and zeroed.
 * The routing id and credential are kept in one RELAY_ACCOUNT row, sealed
 * like every row, so the credential never rests in the clear. (The table's
 * other row, the prekey counters, belongs to PrekeyPublisher and survives
 * forget().)
 *
 * A crash after the registry answered but before the row was written leaves
 * an orphan registration that nothing holds a credential for; the next
 * attempt simply registers again. When the credential is refused later
 * (revoked, or the backend lost it), forget() drops the row and the next
 * ensure() registers afresh - the caller then republishes prekeys and
 * re-allows its conversations' capabilities, because a new registration is a
 * new, empty mailbox.
 */

package com.lumos.app.relay;

import com.lumos.app.identity.IdentityCrypto;
import com.lumos.app.store.LocalStore;
import com.lumos.app.store.RecordKind;
import com.lumos.app.store.StoreException;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.wire.LumosCrypto;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

/** The device's relay registration. */
public final class RelayAccount {

    /** The single row's id. */
    static final String ROW = "self";
    private static final int FORMAT = 1;
    private static final int ENCODED = 1 + RelayClient.ROUTING_ID_SIZE + RelayClient.KEY_SIZE;

    private final LocalStore store;
    private final IdentityCrypto crypto;

    private RelayAccount(final LocalStore store, final IdentityCrypto crypto) {
        this.store = store;
        this.crypto = crypto;
    }

    /**
     * Binds the account to the store and a signing backend.
     *
     * @param store the sealed local store
     * @param crypto the Ed25519 backend (libsodium on Android)
     * @return the account
     */
    public static RelayAccount over(final LocalStore store, final IdentityCrypto crypto) {
        return new RelayAccount(store, crypto);
    }

    /**
     * The exact bytes a device signs to register; the same as the backend's
     * registry.AuthMessage.
     *
     * @param challenge the registry's challenge
     * @param devicePub the device's Ed25519 public key
     * @return the message
     */
    static byte[] authMessage(final byte[] challenge, final byte[] devicePub) {
        return LumosCrypto.concat(LumosConstants.DEVICE_AUTH_SIGNING_INFO_VALUE.getBytes(StandardCharsets.UTF_8),
                challenge, devicePub);
    }

    /**
     * The stored registration, if there is one.
     *
     * @return the registration
     * @throws StoreException on engine failure or an unreadable row
     */
    public Optional<RelayTypes.Registration> current() throws StoreException {
        final Optional<byte[]> row = store.get(RecordKind.RELAY_ACCOUNT, ROW);
        if (!row.isPresent()) {
            return Optional.empty();
        }
        final byte[] b = row.get();
        if (b.length != ENCODED || b[0] != FORMAT) {
            throw new StoreException(StoreException.Reason.ENGINE, "relay account row is unreadable");
        }
        return Optional.of(new RelayTypes.Registration(
                Arrays.copyOfRange(b, 1, 1 + RelayClient.ROUTING_ID_SIZE),
                Arrays.copyOfRange(b, 1 + RelayClient.ROUTING_ID_SIZE, ENCODED)));
    }

    /**
     * The stored registration, registering first when there is none.
     *
     * @param anonymous a client with no credential
     * @return the registration
     * @throws RelayException when the registry could not be reached or refused
     * @throws StoreException on engine failure
     * @throws GeneralSecurityException when the device key could not be made or used
     */
    public RelayTypes.Registration ensure(final RelayClient anonymous)
            throws RelayException, StoreException, GeneralSecurityException {
        final Optional<RelayTypes.Registration> have = current();
        if (have.isPresent()) {
            return have.get();
        }
        final byte[] challenge = anonymous.challenge();
        final IdentityCrypto.KeyMaterial key = crypto.generateEd25519();
        final RelayTypes.Registration reg;
        try {
            final byte[] devicePub = key.rawPublic();
            final byte[] sig = crypto.signBinding(key.pkcs8(), authMessage(challenge, devicePub));
            reg = anonymous.register(challenge, devicePub, sig);
        } finally {
            key.destroy();
        }
        final byte[] row = LumosCrypto.concat(new byte[] {FORMAT}, reg.routingId(), reg.credential());
        store.put(RecordKind.RELAY_ACCOUNT, ROW, row, null);
        Arrays.fill(row, (byte) 0);
        return reg;
    }

    /**
     * Drops the stored registration, after the registry refused its
     * credential, so the next ensure() registers afresh.
     *
     * @throws StoreException on engine failure
     */
    public void forget() throws StoreException {
        store.delete(RecordKind.RELAY_ACCOUNT, ROW);
    }
}

/*
 * Lumos - android-app/app / This install's own HELLO, from its stored identity.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-PROTO-017, SRS-IDN-002, SRS-NBY-005
 * ADRs: ADR-002, ADR-008, ADR-013
 * Threading: any
 * Notes: The DiscoveryEngine's Identity on a device: every link gets a fresh
 * HelloExchange over the install's real keys - the Ed25519 identity public
 * key, the X25519 identity-DH public key and its ADR-008 binding signature,
 * all from the stored IdentityKeyRecord, signed through the unlocked
 * identity's signer (libsodium on Android). Nothing private is held here.
 *
 * Three fields carry nothing yet, on purpose: the EID is all zeros (this
 * phone advertises UUID-only, "zeros when hintless" in the HELLO layout),
 * the venue hint is 0 (no venue context before the venue slices), and the
 * capability bits are 0 (no profile capabilities are composed yet; the
 * feed's interest filter is opt-in and accepts every peer by default). The
 * nonce is set per link by HelloExchange.start.
 */

package com.lumos.app.ble;

import com.lumos.app.identity.IdentityKeyRecord;
import com.lumos.protocol.LumosConstants;
import com.lumos.protocol.hello.Hello;
import com.lumos.protocol.hello.HelloExchange;

/** This install as discovery's identity. */
public final class NearbyIdentity implements DiscoveryEngine.Identity {

    /** No capability claims until the profile composes them. */
    static final int NO_CAPABILITIES = 0;

    /** No venue context before the venue slices. */
    static final long NO_VENUE = 0L;

    private final Hello.Fields own;
    private final Hello.Signer signer;

    /**
     * @param record the stored identity (public halves and the binding signature)
     * @param signer signs with the identity's Ed25519 key
     */
    public NearbyIdentity(final IdentityKeyRecord record, final Hello.Signer signer) {
        this.own = new Hello.Fields(LumosConstants.PROTOCOL_VERSION_ENCODED, NO_CAPABILITIES, NO_VENUE,
                new byte[Hello.EID_LEN], record.ed25519Public(), record.x25519Public(), record.bindingSignature(),
                new byte[Hello.NONCE_LEN]);
        this.signer = signer;
    }

    @Override
    public HelloExchange freshExchange() {
        return new HelloExchange(signer, own);
    }
}

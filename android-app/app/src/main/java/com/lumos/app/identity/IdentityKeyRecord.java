/*
 * Lumos - android-app/app / IdentityKeyRecord: the persisted device identity.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: SRS-IDN-001, SRS-IDN-002, SRS-NFR-SEC-003
 * ADRs: ADR-003, ADR-008, ADR-010
 * Threading: any
 * Notes: The TRS Sec. 8.1 IdentityKeyRecord entity, in its at-rest form. Public
 * halves and the ADR-008 binding signature are plaintext; BOTH private keys
 * exist only inside one KEK-wrapped envelope. There is deliberately no accessor
 * that returns private material - unwrapping happens inside the operations that
 * need it (a later slice's signing/agreement paths), never through this record.
 * Peer identifiers derive from this material and nothing else (SRS-IDN-002): no
 * MAC address, phone number or store-account identifier is anywhere near it.
 */

package com.lumos.app.identity;

import com.lumos.app.keystore.KeyProtection;

/**
 * The device's long-term identity in its encrypted at-rest form: two public
 * keys, the ADR-008 binding signature, and one wrapped private-key envelope.
 */
public final class IdentityKeyRecord {

    private final byte[] ed25519Public;
    private final byte[] x25519Public;
    private final byte[] bindingSignature;
    private final byte[] wrappedPrivateKeys;
    private final KeyProtection protection;

    /**
     * Assembles a record from its parts; every array is defensively copied.
     *
     * @param ed25519Public the raw 32-byte Ed25519 identity public key
     * @param x25519Public the raw 32-byte X25519 identity-DH public key
     * @param bindingSignature the ADR-008 Ed25519 signature binding the two
     * @param wrappedPrivateKeys the KEK-wrapped private-key envelope
     * @param protection the KEK's recorded protection tier (threat model LT-05)
     */
    public IdentityKeyRecord(final byte[] ed25519Public, final byte[] x25519Public,
            final byte[] bindingSignature, final byte[] wrappedPrivateKeys,
            final KeyProtection protection) {
        this.ed25519Public = ed25519Public.clone();
        this.x25519Public = x25519Public.clone();
        this.bindingSignature = bindingSignature.clone();
        this.wrappedPrivateKeys = wrappedPrivateKeys.clone();
        this.protection = protection;
    }

    /**
     * The Ed25519 identity public key - the sole root of trust (ADR-008).
     *
     * @return a copy of the raw 32 bytes
     */
    public byte[] ed25519Public() {
        return ed25519Public.clone();
    }

    /**
     * The X25519 identity-DH public key.
     *
     * @return a copy of the raw 32 bytes
     */
    public byte[] x25519Public() {
        return x25519Public.clone();
    }

    /**
     * The Ed25519 signature binding the identity-DH key to the identity.
     *
     * @return a copy of the 64-byte signature
     */
    public byte[] bindingSignature() {
        return bindingSignature.clone();
    }

    /**
     * The KEK-wrapped envelope holding both private keys.
     *
     * @return a copy of the wrapped bytes; opening it requires the KEK
     */
    public byte[] wrappedPrivateKeys() {
        return wrappedPrivateKeys.clone();
    }

    /**
     * The protection tier recorded when the wrapping KEK was created.
     *
     * @return the recorded tier, never an assumption
     */
    public KeyProtection protection() {
        return protection;
    }
}

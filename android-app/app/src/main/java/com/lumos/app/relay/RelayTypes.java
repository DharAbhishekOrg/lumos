/*
 * Lumos - android-app/app / The values the relay client sends and receives.
 * Author: dhar.abhishek.email@gmail.com
 * Implements: TRS-BCK-001, TRS-BCK-003, TRS-BCK-007
 * ADRs: ADR-001
 * Threading: any
 * Notes: Immutable values: every array is copied in and copied out, so no
 * caller can change a credential, an id or a payload behind another's back.
 */

package com.lumos.app.relay;

/** The relay client's values. */
public final class RelayTypes {

    private RelayTypes() { }

    /**
     * A registration.
     *
     * @param routingId this device's mailbox address
     * @param credential the bearer credential
     */
    public record Registration(byte[] routingId, byte[] credential) {

        /**
         * Copies.
         *
         * @param routingId see the record
         * @param credential see the record
         */
        public Registration {
            routingId = routingId.clone();
            credential = credential.clone();
        }

        /** @return a copy of the routing id */
        @Override
        public byte[] routingId() {
            return routingId.clone();
        }

        /** @return a copy of the credential */
        @Override
        public byte[] credential() {
            return credential.clone();
        }
    }

    /**
     * One one-time prekey's public half.
     *
     * @param id the key id
     * @param publicKey the X25519 public key
     */
    public record OneTimeKey(long id, byte[] publicKey) {

        /**
         * Copies.
         *
         * @param id see the record
         * @param publicKey see the record
         */
        public OneTimeKey {
            publicKey = publicKey.clone();
        }

        /** @return a copy of the public key */
        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }
    }

    /**
     * This device's one-time prekey stock.
     *
     * @param remaining how many the registry still holds
     * @param needsReplenish true below the register threshold
     */
    public record PrekeyStatus(long remaining, boolean needsReplenish) { }

    /**
     * One send.
     *
     * @param recipient the peer's routing id
     * @param messageId the idempotency key
     * @param messageClass the canonical class byte
     * @param capability the send capability the peer issued
     * @param payload the sealed envelope
     */
    public record Send(byte[] recipient, byte[] messageId, int messageClass, byte[] capability, byte[] payload) {

        /**
         * Copies.
         *
         * @param recipient see the record
         * @param messageId see the record
         * @param messageClass see the record
         * @param capability see the record
         * @param payload see the record
         */
        public Send {
            recipient = recipient.clone();
            messageId = messageId.clone();
            capability = capability.clone();
            payload = payload.clone();
        }

        /** @return a copy of the recipient */
        @Override
        public byte[] recipient() {
            return recipient.clone();
        }

        /** @return a copy of the message id */
        @Override
        public byte[] messageId() {
            return messageId.clone();
        }

        /** @return a copy of the capability */
        @Override
        public byte[] capability() {
            return capability.clone();
        }

        /** @return a copy of the payload */
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /**
     * One fetched envelope.
     *
     * @param messageId the id it was sent under
     * @param messageClass the canonical class byte
     * @param senderProof the hash of the capability the sender presented
     * @param createdAt seconds since the epoch
     * @param expiresAt seconds since the epoch
     * @param payload the sealed envelope
     */
    public record Envelope(byte[] messageId, int messageClass, byte[] senderProof, long createdAt, long expiresAt,
            byte[] payload) {

        /**
         * Copies.
         *
         * @param messageId see the record
         * @param messageClass see the record
         * @param senderProof see the record
         * @param createdAt see the record
         * @param expiresAt see the record
         * @param payload see the record
         */
        public Envelope {
            messageId = messageId.clone();
            senderProof = senderProof.clone();
            payload = payload.clone();
        }

        /** @return a copy of the message id */
        @Override
        public byte[] messageId() {
            return messageId.clone();
        }

        /** @return a copy of the sender proof */
        @Override
        public byte[] senderProof() {
            return senderProof.clone();
        }

        /** @return a copy of the payload */
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}

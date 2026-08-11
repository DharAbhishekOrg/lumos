// Lumos - protocol/media / Object pipeline: whole-object AEAD, chunk plan, SACK.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-CRY-008, TRS-PROTO-010, TRS-PROTO-011, TRS-MED-002, SRS-TRN-009
// ADRs: ADR-001
// Threading: any
// Notes: The object is encrypted WHOLE, before fragmentation (TRS-CRY-008), so a
// chunk boundary never becomes a crypto boundary and a partial download reveals
// nothing. The declared digest is over the PLAINTEXT, so a reassembly that
// decrypts cleanly but reconstructs the wrong bytes is still caught. Object keys
// are wrapped to the active match session: an object key lifted out of one
// conversation is useless in another. Media is local-only in flagship
// (SRS-TRN-009) - nothing here may be handed to the internet relay.
package media

import (
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/binary"

	"github.com/dharabhishekemail-ops/lumos/protocol/crypto/aead"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekemail-ops/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekemail-ops/lumos/protocol/wire"
)

// ObjectKeySize is the random per-object key length.
const ObjectKeySize = 32

// Meta is the OBJECT_META description a receiver validates against.
type Meta struct {
	// PlaintextSize is the original object size in bytes.
	PlaintextSize int
	// PlaintextSHA256 is the digest of the ORIGINAL bytes (TRS-MED-002).
	PlaintextSHA256 [32]byte
	// CiphertextSize is the sealed size, which is what the chunk plan covers.
	CiphertextSize int
	// ChunkSize and ChunkCount describe the fragmentation plan.
	ChunkSize  int
	ChunkCount int
	// WrappedKey is the object key sealed to the active match session.
	WrappedKey []byte
}

// deriveObjectAEAD expands the random object key into AEAD key material.
func deriveObjectAEAD(objectKey []byte) (*aead.MessageKeys, error) {
	if len(objectKey) != ObjectKeySize {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaObjectKeyUnwrapFailed, Detail: "object key size"}
	}
	okm, err := hkdf.Key(sha256.New, objectKey, make([]byte, 32),
		lumosconstants.MediaObjectKeyInfoValue, aead.KeySize+aead.NonceSize)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaObjectKeyUnwrapFailed, Detail: "object key derivation"}
	}
	mk := &aead.MessageKeys{}
	copy(mk.Key[:], okm[:aead.KeySize])
	copy(mk.Nonce[:], okm[aead.KeySize:])
	aead.Zero(okm)
	return mk, nil
}

// deriveWrapKeys derives the wrapping key/nonce binding an object key to a
// session. sessionKey is session-scoped secret material; objectID separates
// wraps within one session so two objects never share a wrap nonce.
func deriveWrapKeys(sessionKey []byte, objectID [16]byte) (*aead.MessageKeys, error) {
	info := append([]byte(lumosconstants.MediaObjectKeyWrapInfoValue), objectID[:]...)
	okm, err := hkdf.Key(sha256.New, sessionKey, make([]byte, 32), string(info), aead.KeySize+aead.NonceSize)
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaObjectKeyUnwrapFailed, Detail: "wrap key derivation"}
	}
	mk := &aead.MessageKeys{}
	copy(mk.Key[:], okm[:aead.KeySize])
	copy(mk.Nonce[:], okm[aead.KeySize:])
	aead.Zero(okm)
	return mk, nil
}

// WrapObjectKey seals an object key to the active match session (TRS-CRY-008).
func WrapObjectKey(sessionKey []byte, objectID [16]byte, objectKey []byte) ([]byte, error) {
	mk, err := deriveWrapKeys(sessionKey, objectID)
	if err != nil {
		return nil, err
	}
	return aead.Seal(mk.Key[:], mk.Nonce[:], objectKey, objectID[:])
}

// UnwrapObjectKey recovers an object key. A wrap from a different session, or a
// different object ID, fails closed with the stable media code.
func UnwrapObjectKey(sessionKey []byte, objectID [16]byte, wrapped []byte) ([]byte, error) {
	mk, err := deriveWrapKeys(sessionKey, objectID)
	if err != nil {
		return nil, err
	}
	key, err := aead.Open(mk.Key[:], mk.Nonce[:], wrapped, objectID[:])
	if err != nil {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaObjectKeyUnwrapFailed, Detail: "object key rejected"}
	}
	return key, nil
}

// ChunkPlan computes the fragmentation plan for a sealed object. chunkSize
// comes from the transport in use; the caller passes the negotiated value.
func ChunkPlan(ciphertextSize, chunkSize int) (count int, err error) {
	if chunkSize <= 0 {
		return 0, &wire.Error{Code: lumoserrors.ErrMediaChunkOutOfRange, Detail: "chunk size"}
	}
	if ciphertextSize < 0 {
		return 0, &wire.Error{Code: lumoserrors.ErrMediaChunkOutOfRange, Detail: "object size"}
	}
	return (ciphertextSize + chunkSize - 1) / chunkSize, nil
}

// Seal encrypts a whole object and produces its metadata and chunk plan.
// objectID binds the wrap; sessionKey is the active match session's secret.
func Seal(sessionKey []byte, objectID [16]byte, objectKey, plaintext []byte, chunkSize int) (*Meta, []byte, error) {
	mk, err := deriveObjectAEAD(objectKey)
	if err != nil {
		return nil, nil, err
	}
	// The object ID is the AEAD associated data, binding ciphertext to identity.
	ciphertext, err := aead.Seal(mk.Key[:], mk.Nonce[:], plaintext, objectID[:])
	if err != nil {
		return nil, nil, err
	}
	wrapped, err := WrapObjectKey(sessionKey, objectID, objectKey)
	if err != nil {
		return nil, nil, err
	}
	count, err := ChunkPlan(len(ciphertext), chunkSize)
	if err != nil {
		return nil, nil, err
	}
	meta := &Meta{
		PlaintextSize:   len(plaintext),
		PlaintextSHA256: sha256.Sum256(plaintext),
		CiphertextSize:  len(ciphertext),
		ChunkSize:       chunkSize,
		ChunkCount:      count,
		WrappedKey:      wrapped,
	}
	return meta, ciphertext, nil
}

// Chunk returns chunk i of a sealed object.
func Chunk(ciphertext []byte, chunkSize, i int) ([]byte, error) {
	if chunkSize <= 0 || i < 0 || i*chunkSize >= len(ciphertext)+chunkSize {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaChunkOutOfRange, Detail: "chunk index"}
	}
	start := i * chunkSize
	if start >= len(ciphertext) {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaChunkOutOfRange, Detail: "chunk index"}
	}
	end := start + chunkSize
	if end > len(ciphertext) {
		end = len(ciphertext)
	}
	return ciphertext[start:end], nil
}

// Open reassembles, decrypts and verifies an object against its metadata. The
// digest check is over the decrypted plaintext, so a clean decrypt of the wrong
// reassembly still fails (TRS-MED-002).
func Open(sessionKey []byte, objectID [16]byte, meta *Meta, ciphertext []byte) ([]byte, error) {
	if len(ciphertext) != meta.CiphertextSize {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaDigestMismatch, Detail: "reassembled size"}
	}
	objectKey, err := UnwrapObjectKey(sessionKey, objectID, meta.WrappedKey)
	if err != nil {
		return nil, err
	}
	mk, err := deriveObjectAEAD(objectKey)
	aead.Zero(objectKey)
	if err != nil {
		return nil, err
	}
	plaintext, err := aead.Open(mk.Key[:], mk.Nonce[:], ciphertext, objectID[:])
	if err != nil {
		return nil, err
	}
	if sha256.Sum256(plaintext) != meta.PlaintextSHA256 {
		return nil, &wire.Error{Code: lumoserrors.ErrMediaDigestMismatch, Detail: "object digest"}
	}
	return plaintext, nil
}

// SelectiveAck is a bounded acknowledgement over a chunk plan (TRS-PROTO-011).
// The bitmap is little-endian by chunk index: chunk i is bit (i%8) of byte i/8.
type SelectiveAck struct {
	ChunkCount int
	Bitmap     []byte
}

// NewSelectiveAck allocates an ack covering count chunks.
func NewSelectiveAck(count int) *SelectiveAck {
	return &SelectiveAck{ChunkCount: count, Bitmap: make([]byte, (count+7)/8)}
}

// Mark records a received chunk.
func (s *SelectiveAck) Mark(i int) error {
	if i < 0 || i >= s.ChunkCount {
		return &wire.Error{Code: lumoserrors.ErrMediaChunkOutOfRange, Detail: "ack index"}
	}
	s.Bitmap[i/8] |= 1 << (uint(i) % 8)
	return nil
}

// Has reports whether a chunk is marked received.
func (s *SelectiveAck) Has(i int) bool {
	if i < 0 || i >= s.ChunkCount {
		return false
	}
	return s.Bitmap[i/8]&(1<<(uint(i)%8)) != 0
}

// MissingRanges returns the gaps as inclusive [start,end] pairs - the bounded
// range form of TRS-PROTO-011, used when it is smaller than the bitmap.
func (s *SelectiveAck) MissingRanges() [][2]int {
	var out [][2]int
	i := 0
	for i < s.ChunkCount {
		if s.Has(i) {
			i++
			continue
		}
		start := i
		for i < s.ChunkCount && !s.Has(i) {
			i++
		}
		out = append(out, [2]int{start, i - 1})
	}
	return out
}

// Complete reports whether every chunk is present.
func (s *SelectiveAck) Complete() bool {
	for i := 0; i < s.ChunkCount; i++ {
		if !s.Has(i) {
			return false
		}
	}
	return true
}

// ResumeToken encodes the next chunk to request, so a transfer resumes without
// retransmitting what arrived (TRS-PROTO-011).
func (s *SelectiveAck) ResumeToken() []byte {
	next := s.ChunkCount
	for i := 0; i < s.ChunkCount; i++ {
		if !s.Has(i) {
			next = i
			break
		}
	}
	out := make([]byte, 4)
	binary.BigEndian.PutUint32(out, uint32(next))
	return out
}

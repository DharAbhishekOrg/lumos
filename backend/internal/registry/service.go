// Lumos - backend/internal/registry / Device registration, credentials and the prekey directory.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-005, TRS-BCK-007, TRS-CRY-005, TRS-CRY-013
// ADRs: ADR-001
// Threading: concurrent-safe
// Notes: Registration needs no phone or email (TRS-BCK-007): a device proves it
// holds an Ed25519 key by signing (DEVICE_AUTH_SIGNING_INFO | challenge |
// device_pub) over a server challenge that is single-use and lives
// DEVICE_AUTH_CHALLENGE_TTL_SEC. It receives a random opaque routing id and a
// random access credential; only the credential's SHA-256 is stored, and
// revoking the device kills it. Every auth failure is ONE code,
// ERR_AUTH_REJECTED, so a prober cannot tell a revoked credential from an
// invented one. Uploaded bundles are verified with the same x3dh code the
// clients use; the identity key is pinned at first upload, so a stolen
// credential cannot quietly swap in another identity. Clock and randomness are
// injected so tests are deterministic.
package registry

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"errors"
	"io"
	"sync"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

// maxOutstandingChallenges bounds the challenge table so unauthenticated
// challenge requests cannot grow server memory without limit. Request-rate
// limiting proper is the gateway's job (TRS-BCK-010); this is the backstop.
const maxOutstandingChallenges = 1 << 13

// Clock returns unix seconds.
type Clock func() int64

// Service is the registry's behaviour over a Repository.
type Service struct {
	repo  Repository
	now   Clock
	rand  io.Reader
	mu    sync.Mutex
	chals map[[32]byte]int64 // challenge -> expiry
}

// NewService builds the service. rand must be a CSPRNG in production.
func NewService(repo Repository, now Clock, rand io.Reader) *Service {
	return &Service{repo: repo, now: now, rand: rand, chals: map[[32]byte]int64{}}
}

// Registration is what a device receives once: its routing id and credential.
type Registration struct {
	ID         DeviceID
	Credential [32]byte
}

// Upload is a prekey publication. Bundle may be nil for a pure replenishment.
type Upload struct {
	Bundle  *x3dh.PublicBundle
	OneTime []OneTimePrekey
}

// Status tells a device whether to replenish its one-time prekeys.
type Status struct {
	OneTimeRemaining int
	NeedsReplenish   bool
}

func rejected() error {
	return &wire.Error{Code: lumoserrors.ErrAuthRejected, Detail: "authentication rejected"}
}

func uploadInvalid(detail string) error {
	return &wire.Error{Code: lumoserrors.ErrPrekeyUploadInvalid, Detail: detail}
}

// AuthMessage is the exact byte string a device signs to register.
func AuthMessage(challenge, devicePub [32]byte) []byte {
	msg := []byte(lumosconstants.DeviceAuthSigningInfoValue)
	msg = append(msg, challenge[:]...)
	return append(msg, devicePub[:]...)
}

// IssueChallenge returns a fresh single-use registration challenge.
func (s *Service) IssueChallenge() ([32]byte, error) {
	var c [32]byte
	if _, err := io.ReadFull(s.rand, c[:]); err != nil {
		return c, err
	}
	now := s.now()
	s.mu.Lock()
	defer s.mu.Unlock()
	for k, exp := range s.chals {
		if now > exp {
			delete(s.chals, k)
		}
	}
	if len(s.chals) >= maxOutstandingChallenges {
		return [32]byte{}, rejected()
	}
	s.chals[c] = now + int64(lumosconstants.DeviceAuthChallengeTTLSecDefault)
	return c, nil
}

// Register consumes a challenge and, if the device signed it, creates the
// device. The challenge is spent whether or not the signature verifies.
func (s *Service) Register(ctx context.Context, challenge, devicePub [32]byte, sig []byte) (Registration, error) {
	s.mu.Lock()
	exp, ok := s.chals[challenge]
	delete(s.chals, challenge)
	s.mu.Unlock()
	if !ok || s.now() > exp {
		return Registration{}, rejected()
	}
	if !ed25519.Verify(ed25519.PublicKey(devicePub[:]), AuthMessage(challenge, devicePub), sig) {
		return Registration{}, rejected()
	}
	var reg Registration
	if _, err := io.ReadFull(s.rand, reg.ID[:]); err != nil {
		return Registration{}, err
	}
	if _, err := io.ReadFull(s.rand, reg.Credential[:]); err != nil {
		return Registration{}, err
	}
	err := s.repo.CreateDevice(ctx, Device{
		ID: reg.ID, DeviceKey: devicePub, CredentialHash: sha256.Sum256(reg.Credential[:]), CreatedAt: s.now(),
	})
	if err != nil {
		return Registration{}, err
	}
	return reg, nil
}

// Authenticate resolves a credential to its live device.
func (s *Service) Authenticate(ctx context.Context, credential []byte) (Device, error) {
	d, err := s.repo.DeviceByCredential(ctx, sha256.Sum256(credential))
	if err != nil || d.Revoked {
		return Device{}, rejected()
	}
	return d, nil
}

// Revoke kills a device's credential and withdraws its bundle from claims.
func (s *Service) Revoke(ctx context.Context, id DeviceID) error {
	return s.repo.RevokeDevice(ctx, id)
}

// PublishPrekeys verifies and stores an upload for an authenticated device.
func (s *Service) PublishPrekeys(ctx context.Context, d Device, up Upload) error {
	for _, k := range up.OneTime {
		if k.Key == ([32]byte{}) {
			return uploadInvalid("one-time prekey is all zero")
		}
	}
	if up.Bundle != nil {
		if err := s.publishBundle(ctx, d, up.Bundle); err != nil {
			return err
		}
	} else if _, _, err := s.peekBundle(ctx, d.ID); err != nil {
		return uploadInvalid("replenishment before any signed bundle")
	}
	if len(up.OneTime) == 0 {
		return nil
	}
	err := s.repo.AddOneTimePrekeys(ctx, d.ID, up.OneTime, lumosconstants.OneTimePrekeysUploadedMax)
	switch {
	case errors.Is(err, ErrDuplicate):
		return uploadInvalid("one-time prekey id already used")
	case errors.Is(err, ErrStockCeiling):
		return uploadInvalid("one-time prekey stock would exceed the register ceiling")
	}
	return err
}

func (s *Service) publishBundle(ctx context.Context, d Device, pb *x3dh.PublicBundle) error {
	parsed, err := x3dh.ParsePublicBundle(*pb)
	if err != nil {
		return err
	}
	if err := x3dh.VerifyBundle(parsed); err != nil {
		return err
	}
	if prev, ok, _ := s.peekBundle(ctx, d.ID); ok && !bytes.Equal(prev.IdentityEd[:], pb.IdentityEd) {
		return uploadInvalid("identity key differs from the pinned identity")
	}
	var b SignedBundle
	copy(b.IdentityEd[:], pb.IdentityEd)
	copy(b.IdentityDH[:], pb.IdentityDH)
	copy(b.IdentityDHSig[:], pb.IdentityDHSig)
	b.SPKID = pb.SPKID
	copy(b.SPK[:], pb.SPK)
	copy(b.SPKSig[:], pb.SPKSig)
	b.UploadedAt = s.now()
	return s.repo.PutSignedBundle(ctx, d.ID, b)
}

// peekBundle reads the current bundle without consuming a one-time prekey.
func (s *Service) peekBundle(ctx context.Context, id DeviceID) (SignedBundle, bool, error) {
	b, err := s.repo.CurrentBundle(ctx, id)
	if errors.Is(err, ErrNotFound) {
		return SignedBundle{}, false, err
	}
	return b, err == nil, err
}

// ClaimBundle hands an authenticated requester the target's bundle and at
// most one one-time prekey, never the same one twice (TRS-CRY-005). An
// unknown, revoked or bundle-less target is indistinguishable: ErrNotFound.
func (s *Service) ClaimBundle(ctx context.Context, _ Device, target DeviceID) (SignedBundle, *OneTimePrekey, error) {
	return s.repo.ClaimBundle(ctx, target)
}

// PrekeyStatus reports the caller's own stock against the register threshold.
func (s *Service) PrekeyStatus(ctx context.Context, d Device) (Status, error) {
	n, err := s.repo.OneTimePrekeyCount(ctx, d.ID)
	if err != nil {
		return Status{}, err
	}
	return Status{OneTimeRemaining: n, NeedsReplenish: n < lumosconstants.PrekeyReplenishThresholdDefault}, nil
}

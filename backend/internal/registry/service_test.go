// Lumos - backend/internal/registry / Registration, auth and the prekey directory under test.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-005, TRS-BCK-007, TRS-CRY-005, TRS-CRY-013, TRS-TST-001
// ADRs: ADR-001
// Threading: test-only
// Notes: The properties a Repository implementation must keep, driven through
// the service so the PostgreSQL adapter (S4b) can run the same suite: a key
// proof is the whole of registration; every auth failure is one code; the
// store holds a credential's hash, never the credential; a bundle is verified
// and its identity pinned; and a one-time prekey is never issued twice, even
// to hundreds of concurrent claimers.
package registry

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"sync"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/crypto/x3dh"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumoserrors"
	"github.com/dharabhishekorg/lumos/protocol/wire"
)

type fixture struct {
	repo interface {
		Repository
		credentialHashes(ctx context.Context) ([][32]byte, error)
	}
	svc *Service
	now int64
}

// eachRepo runs one contract against every Repository implementation, so the
// PostgreSQL adapter is held to exactly the properties the memory one is.
func eachRepo(t *testing.T, run func(t *testing.T, f *fixture)) {
	t.Helper()
	build := func(repo interface {
		Repository
		credentialHashes(ctx context.Context) ([][32]byte, error)
	}) *fixture {
		f := &fixture{repo: repo, now: 1_700_000_000}
		f.svc = NewService(f.repo, func() int64 { return f.now }, rand.Reader)
		return f
	}
	t.Run("memory", func(t *testing.T) { run(t, build(NewMemoryRepository())) })
	t.Run("postgres", func(t *testing.T) { run(t, build(NewPostgresRepository(postgresPool(t)))) })
}

func code(err error) string {
	var we *wire.Error
	if errors.As(err, &we) {
		return we.Code
	}
	if err == nil {
		return ""
	}
	return err.Error()
}

// register runs the whole key-proof flow for a fresh device key.
func (f *fixture) register(t *testing.T) (Registration, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ch, err := f.svc.IssueChallenge()
	if err != nil {
		t.Fatal(err)
	}
	var dp [32]byte
	copy(dp[:], pub)
	reg, err := f.svc.Register(context.Background(), ch, dp, ed25519.Sign(priv, AuthMessage(ch, dp)))
	if err != nil {
		t.Fatalf("Register: %v", err)
	}
	return reg, priv
}

func (f *fixture) device(t *testing.T, reg Registration) Device {
	t.Helper()
	d, err := f.svc.Authenticate(context.Background(), reg.Credential[:])
	if err != nil {
		t.Fatalf("Authenticate: %v", err)
	}
	return d
}

// bundle signs a valid public bundle under identity with a fresh signed prekey.
func bundle(t *testing.T, identity ed25519.PrivateKey, spkID uint32) *x3dh.PublicBundle {
	t.Helper()
	idDH, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	spk, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return &x3dh.PublicBundle{
		IdentityEd:    identity.Public().(ed25519.PublicKey),
		IdentityDH:    idDH.PublicKey().Bytes(),
		IdentityDHSig: x3dh.SignIdentityDH(identity, idDH.PublicKey()),
		SPKID:         spkID,
		SPK:           spk.PublicKey().Bytes(),
		SPKSig:        x3dh.SignPrekey(identity, spkID, spk.PublicKey()),
	}
}

func opks(t *testing.T, from, n int) []OneTimePrekey {
	t.Helper()
	out := make([]OneTimePrekey, n)
	for i := range out {
		k, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		out[i].ID = uint32(from + i) // #nosec G115 -- test ids are small and positive
		copy(out[i].Key[:], k.PublicKey().Bytes())
	}
	return out
}

func TestRegistrationIsAKeyProofAndTheStoreNeverHoldsTheCredential(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		reg, _ := f.register(t)
		d := f.device(t, reg)
		if d.ID != reg.ID {
			t.Fatal("the credential must resolve to the device it was issued to")
		}
		hashes, err := f.repo.credentialHashes(context.Background())
		if err != nil || len(hashes) != 1 {
			t.Fatalf("want exactly one stored credential hash, got %d (%v)", len(hashes), err)
		}
		for _, h := range hashes {
			if h == reg.Credential || bytes.Contains(h[:], reg.Credential[:]) {
				t.Fatal("the store must hold the credential's hash, never the credential")
			}
		}
		if reg.ID == (DeviceID{}) {
			t.Fatal("the routing id must be random, not zero")
		}
	})
}

func TestEveryAuthFailureIsTheSameCodeAndDetail(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		pub, priv, _ := ed25519.GenerateKey(rand.Reader)
		var dp [32]byte
		copy(dp[:], pub)
		_, otherPriv, _ := ed25519.GenerateKey(rand.Reader)

		var failures []error
		// Unknown challenge.
		var invented [32]byte
		_, err := f.svc.Register(ctx, invented, dp, ed25519.Sign(priv, AuthMessage(invented, dp)))
		failures = append(failures, err)
		// Signature by a different key: the challenge is spent anyway.
		ch, _ := f.svc.IssueChallenge()
		_, err = f.svc.Register(ctx, ch, dp, ed25519.Sign(otherPriv, AuthMessage(ch, dp)))
		failures = append(failures, err)
		_, err = f.svc.Register(ctx, ch, dp, ed25519.Sign(priv, AuthMessage(ch, dp)))
		failures = append(failures, err) // a spent challenge never works, even correctly signed
		// Expired challenge.
		ch, _ = f.svc.IssueChallenge()
		f.now += int64(lumosconstants.DeviceAuthChallengeTTLSecDefault) + 1
		_, err = f.svc.Register(ctx, ch, dp, ed25519.Sign(priv, AuthMessage(ch, dp)))
		failures = append(failures, err)
		// Invented credential, and a revoked one.
		_, err = f.svc.Authenticate(ctx, invented[:])
		failures = append(failures, err)
		reg, _ := f.register(t)
		if err := f.svc.Revoke(ctx, reg.ID); err != nil {
			t.Fatal(err)
		}
		_, err = f.svc.Authenticate(ctx, reg.Credential[:])
		failures = append(failures, err)

		for i, err := range failures {
			if code(err) != lumoserrors.ErrAuthRejected {
				t.Fatalf("failure %d: want %s, got %v", i, lumoserrors.ErrAuthRejected, err)
			}
			if err.Error() != failures[0].Error() {
				t.Fatalf("failure %d says %q, failure 0 says %q: causes must be indistinguishable",
					i, err.Error(), failures[0].Error())
			}
		}
	})
}

func TestABundleIsVerifiedAndItsIdentityPinned(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		d := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		_, identity, _ := ed25519.GenerateKey(rand.Reader)

		forged := bundle(t, identity, 1)
		forged.SPKSig = bytes.Repeat([]byte{7}, 64)
		if got := code(f.svc.PublishPrekeys(ctx, d, Upload{Bundle: forged})); got != lumoserrors.ErrCryptoPrekeySignatureInvalid {
			t.Fatalf("a forged prekey signature: want %s, got %s", lumoserrors.ErrCryptoPrekeySignatureInvalid, got)
		}
		if err := f.svc.PublishPrekeys(ctx, d, Upload{Bundle: bundle(t, identity, 1)}); err != nil {
			t.Fatalf("a valid bundle: %v", err)
		}
		if err := f.svc.PublishPrekeys(ctx, d, Upload{Bundle: bundle(t, identity, 2)}); err != nil {
			t.Fatalf("rotating the signed prekey under the same identity: %v", err)
		}
		_, intruder, _ := ed25519.GenerateKey(rand.Reader)
		if got := code(f.svc.PublishPrekeys(ctx, d, Upload{Bundle: bundle(t, intruder, 3)})); got != lumoserrors.ErrPrekeyUploadInvalid {
			t.Fatalf("a different identity under the same credential: want %s, got %s", lumoserrors.ErrPrekeyUploadInvalid, got)
		}
	})
}

func TestOneTimePrekeysAreNeverIssuedTwiceUnderConcurrentClaims(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		owner := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		claimer := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		_, identity, _ := ed25519.GenerateKey(rand.Reader)
		const stock = 150
		if err := f.svc.PublishPrekeys(ctx, owner, Upload{Bundle: bundle(t, identity, 1), OneTime: opks(t, 0, stock)}); err != nil {
			t.Fatal(err)
		}

		const claimers = 2 * stock
		var mu sync.Mutex
		seen := map[uint32]int{}
		fallbacks := 0
		var wg sync.WaitGroup
		for range claimers {
			wg.Add(1)
			go func() {
				defer wg.Done()
				_, k, err := f.svc.ClaimBundle(ctx, claimer, owner.ID)
				mu.Lock()
				defer mu.Unlock()
				switch {
				case err != nil:
					t.Errorf("claim: %v", err)
				case k == nil:
					fallbacks++
				default:
					seen[k.ID]++
				}
			}()
		}
		wg.Wait()
		for id, n := range seen {
			if n != 1 {
				t.Fatalf("one-time prekey %d was issued %d times", id, n)
			}
		}
		if len(seen) != stock || fallbacks != claimers-stock {
			t.Fatalf("issued %d distinct keys and %d fallbacks; want %d and %d", len(seen), fallbacks, stock, claimers-stock)
		}
	})
}

func TestAConsumedPrekeyIdIsNeverAcceptedAgain(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		owner := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		_, identity, _ := ed25519.GenerateKey(rand.Reader)
		if err := f.svc.PublishPrekeys(ctx, owner, Upload{Bundle: bundle(t, identity, 1), OneTime: opks(t, 7, 1)}); err != nil {
			t.Fatal(err)
		}
		if _, k, _ := f.svc.ClaimBundle(ctx, owner, owner.ID); k == nil || k.ID != 7 {
			t.Fatal("the one key in stock must be claimed")
		}
		if got := code(f.svc.PublishPrekeys(ctx, owner, Upload{OneTime: opks(t, 7, 1)})); got != lumoserrors.ErrPrekeyUploadInvalid {
			t.Fatalf("re-uploading a consumed id: want %s, got %s", lumoserrors.ErrPrekeyUploadInvalid, got)
		}
		if got := code(f.svc.PublishPrekeys(ctx, owner, Upload{OneTime: append(opks(t, 20, 1), opks(t, 20, 1)...)})); got != lumoserrors.ErrPrekeyUploadInvalid {
			t.Fatalf("a duplicate id within one batch: want %s, got %s", lumoserrors.ErrPrekeyUploadInvalid, got)
		}
	})
}

func TestTheStockCeilingComesFromTheRegisterAndIsAllOrNothing(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		owner := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		_, identity, _ := ed25519.GenerateKey(rand.Reader)
		over := opks(t, 0, lumosconstants.OneTimePrekeysUploadedMax+1)
		if got := code(f.svc.PublishPrekeys(ctx, owner, Upload{Bundle: bundle(t, identity, 1), OneTime: over})); got != lumoserrors.ErrPrekeyUploadInvalid {
			t.Fatalf("a batch above ONE_TIME_PREKEYS_UPLOADED.max: want %s, got %s", lumoserrors.ErrPrekeyUploadInvalid, got)
		}
		if st, _ := f.svc.PrekeyStatus(ctx, owner); st.OneTimeRemaining != 0 {
			t.Fatalf("a refused batch must store nothing; %d stored", st.OneTimeRemaining)
		}
	})
}

func TestTheReplenishSignalFollowsTheRegisterThreshold(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		owner := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		_, identity, _ := ed25519.GenerateKey(rand.Reader)
		threshold := lumosconstants.PrekeyReplenishThresholdDefault
		if err := f.svc.PublishPrekeys(ctx, owner, Upload{Bundle: bundle(t, identity, 1), OneTime: opks(t, 0, threshold)}); err != nil {
			t.Fatal(err)
		}
		if st, _ := f.svc.PrekeyStatus(ctx, owner); st.NeedsReplenish {
			t.Fatalf("exactly the threshold (%d) in stock is not yet low", threshold)
		}
		if _, _, err := f.svc.ClaimBundle(ctx, owner, owner.ID); err != nil {
			t.Fatal(err)
		}
		if st, _ := f.svc.PrekeyStatus(ctx, owner); !st.NeedsReplenish || st.OneTimeRemaining != threshold-1 {
			t.Fatalf("one below the threshold must ask for more: %+v", st)
		}
	})
}

func TestReplenishmentBeforeABundleAndClaimsOnARevokedDeviceAreRefused(t *testing.T) {
	eachRepo(t, func(t *testing.T, f *fixture) {
		ctx := context.Background()
		owner := f.device(t, func() Registration { r, _ := f.register(t); return r }())
		if got := code(f.svc.PublishPrekeys(ctx, owner, Upload{OneTime: opks(t, 0, 5)})); got != lumoserrors.ErrPrekeyUploadInvalid {
			t.Fatalf("one-time keys with no signed bundle: want %s, got %s", lumoserrors.ErrPrekeyUploadInvalid, got)
		}
		_, identity, _ := ed25519.GenerateKey(rand.Reader)
		if err := f.svc.PublishPrekeys(ctx, owner, Upload{Bundle: bundle(t, identity, 1)}); err != nil {
			t.Fatal(err)
		}
		if err := f.svc.Revoke(ctx, owner.ID); err != nil {
			t.Fatal(err)
		}
		if _, _, err := f.svc.ClaimBundle(ctx, owner, owner.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("a revoked device must not be claimable, got %v", err)
		}
	})
}

// Lumos - protocol/match / The rolling budget under an injected clock.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-011, SRS-SAF-006, TRS-TST-001
// ADRs: ADR-002
// Threading: test-only
// Notes: Numbers computed from the register, never written beside it. The
// cooldown assertions pin both halves of the definition: the respectful
// floor after the budget-spending send, and the truth that the message
// cannot clear while no slot is actually free.

package match

import (
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

func TestTheBudgetIsARollingHour(t *testing.T) {
	b, err := NewBudget()
	if err != nil {
		t.Fatal(err)
	}
	limit := lumosconstants.InterestRateLimitPerHourDefault
	start := int64(1_000_000)
	for i := 0; i < limit; i++ {
		if !b.Allowed(start + int64(i)) {
			t.Fatalf("send %d of %d refused inside the budget", i+1, limit)
		}
		b.Record(start + int64(i))
	}
	if b.Allowed(start + int64(limit)) {
		t.Fatal("the budget-plus-one send must be refused")
	}
	// One millisecond before the first send leaves the window: still full.
	if b.Allowed(start + windowMillis - 1) {
		t.Fatal("the window had not rolled yet")
	}
	// The moment it leaves: one slot frees.
	if !b.Allowed(start + windowMillis) {
		t.Fatal("a slot must free exactly as the window rolls")
	}
}

func TestTheCooldownHoldsItsFloorAndItsTruth(t *testing.T) {
	b, err := NewBudget()
	if err != nil {
		t.Fatal(err)
	}
	limit := lumosconstants.InterestRateLimitPerHourDefault
	start := int64(1_000_000)
	for i := 0; i < limit; i++ {
		b.Record(start + int64(i))
	}
	lastSend := start + int64(limit-1)
	floorMillis := int64(lumosconstants.InterestCooldownSecDefault) * 1000

	if b.CooldownRemainingMillis(start) <= 0 {
		t.Fatal("a spent budget must hold a cooldown")
	}
	// Past the floor but before any slot frees: the message stays, because
	// saying "go ahead" while every slot is taken would be a lie.
	afterFloor := lastSend + floorMillis + 1
	if afterFloor < start+windowMillis {
		if b.CooldownRemainingMillis(afterFloor) <= 0 {
			t.Fatal("the cooldown cleared while no slot was free")
		}
	}
	// Once sending is allowed again, the cooldown reads zero.
	if b.CooldownRemainingMillis(start+windowMillis) != 0 {
		t.Fatal("an allowed budget holds no cooldown")
	}
}

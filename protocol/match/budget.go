// Lumos - protocol/match / The rolling-hour request budget and its cooldown.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-MAT-011, SRS-SAF-006
// ADRs: ADR-002
// Threading: serial-executor
// Notes: A rolling window over injected clocks (TRS-TST-001): the caller
// asks before sending, records after sending, and gets back how long the
// respectful cooldown message should hold once the budget is spent. The
// budget protects RECIPIENTS from flooding, so it counts sends to anyone,
// not per peer - one person spraying a venue is exactly the case SRS-SAF-006
// cares about. Register-bounded at construction like every tuned component.

package match

import (
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/gen/go/lumosconstants"
)

// Budget is the rolling-hour interest-request allowance.
type Budget struct {
	sentAtMillis []int64
}

const windowMillis = 3_600_000

// NewBudget builds a budget, refusing register values outside their bounds.
func NewBudget() (*Budget, error) {
	limit := lumosconstants.InterestRateLimitPerHourDefault
	if limit < lumosconstants.InterestRateLimitPerHourMin ||
		limit > lumosconstants.InterestRateLimitPerHourMax {
		return nil, fmt.Errorf("match: rate limit outside register bounds")
	}
	cooldown := lumosconstants.InterestCooldownSecDefault
	if cooldown < lumosconstants.InterestCooldownSecMin ||
		cooldown > lumosconstants.InterestCooldownSecMax {
		return nil, fmt.Errorf("match: cooldown outside register bounds")
	}
	return &Budget{}, nil
}

// Allowed reports whether one more request may be sent at nowMillis.
func (b *Budget) Allowed(nowMillis int64) bool {
	b.prune(nowMillis)
	return len(b.sentAtMillis) < lumosconstants.InterestRateLimitPerHourDefault
}

// Record notes a sent request at nowMillis.
func (b *Budget) Record(nowMillis int64) {
	b.prune(nowMillis)
	b.sentAtMillis = append(b.sentAtMillis, nowMillis)
}

// CooldownRemainingMillis is how long the SRS-MAT-011 cooldown message
// should still hold at nowMillis: zero when sending is allowed, otherwise
// the LONGER of the INTEREST_COOLDOWN_SEC floor (measured from the send
// that spent the budget) and the time until a slot actually frees - the
// floor keeps the message respectful rather than a countdown to resume
// spraying, and the slot time keeps it true.
func (b *Budget) CooldownRemainingMillis(nowMillis int64) int64 {
	if b.Allowed(nowMillis) {
		return 0
	}
	last := b.sentAtMillis[len(b.sentAtMillis)-1]
	floor := last + int64(lumosconstants.InterestCooldownSecDefault)*1000 - nowMillis
	slot := b.sentAtMillis[0] + windowMillis - nowMillis
	remaining := max(floor, slot)
	if remaining < 0 {
		return 0
	}
	return remaining
}

func (b *Budget) prune(nowMillis int64) {
	kept := b.sentAtMillis[:0]
	for _, at := range b.sentAtMillis {
		if nowMillis-at < windowMillis {
			kept = append(kept, at)
		}
	}
	b.sentAtMillis = kept
}

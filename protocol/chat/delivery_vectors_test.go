// Lumos - protocol/chat / The committed delivery timelines, replayed.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, TRS-TST-001
// ADRs: ADR-002
// Threading: test-only
// Notes: Reads the COMMITTED file, so a hand-edited vector, a stale
// constants_hash or a machine drift fails here; the family-level scan
// re-asserts the guardrail over the vectors as data: no step reaches
// delivered or read except on a recipient receipt.

package chat

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/dharabhishekorg/lumos/protocol/schema"
)

type deliveryVectorFile struct {
	ConstantsHash string `json:"constants_hash"`
	Ticks         []struct {
		State string `json:"state"`
		Tick  string `json:"tick"`
	} `json:"ticks"`
	Scripts []struct {
		Name  string `json:"name"`
		Steps []struct {
			Event       string `json:"event"`
			ExpectState string `json:"expect_state"`
			ExpectTick  string `json:"expect_tick"`
		} `json:"steps"`
	} `json:"scripts"`
	ReceiptPolicy []struct {
		ReadReceiptsEnabled bool `json:"read_receipts_enabled"`
		Opened              bool `json:"opened"`
		SendDelivery        bool `json:"send_delivery"`
		SendRead            bool `json:"send_read"`
	} `json:"receipt_policy"`
}

var vectorStateNames = map[State]string{
	Queued: "queued", Sending: "sending", Sent: "sent", Delivered: "delivered",
	Read: "read", Failed: "failed", LocalOnlyWaiting: "local_only_waiting",
}

var vectorEvents = map[string]Event{
	"send_started": SendStarted, "transport_accepted": TransportAccepted,
	"transport_retryable": TransportRetryable, "attempts_exhausted": AttemptsExhausted,
	"delivery_receipt": DeliveryReceipt, "read_receipt": ReadReceipt,
	"user_retry": UserRetry, "local_path_unavailable": LocalPathUnavailable,
	"local_path_available": LocalPathAvailable,
}

func loadDeliveryVectors(t *testing.T) deliveryVectorFile {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "test-vectors", "chat", "delivery_states.json"))
	if err != nil {
		t.Fatalf("committed vector file must exist: %v", err)
	}
	var file deliveryVectorFile
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatalf("unparseable vector file: %v", err)
	}
	reg, err := schema.LoadRegister(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	declared, err := reg.String("constants_hash")
	if err != nil {
		t.Fatal(err)
	}
	if file.ConstantsHash != declared {
		t.Fatal("vector constants_hash does not match the register - stale vectors (TRS-CONST-005)")
	}
	if len(file.Ticks) == 0 || len(file.Scripts) == 0 || len(file.ReceiptPolicy) == 0 {
		t.Fatal("an empty section proves nothing")
	}
	return file
}

func TestCommittedTicksMatchTheReference(t *testing.T) {
	file := loadDeliveryVectors(t)
	for _, c := range file.Ticks {
		found := false
		for s, name := range vectorStateNames {
			if name == c.State {
				found = true
				if string(TickOf(s)) != c.Tick {
					t.Errorf("%s: tick %s, vector %s", c.State, TickOf(s), c.Tick)
				}
			}
		}
		if !found {
			t.Errorf("unknown state %q in vectors", c.State)
		}
	}
}

func TestCommittedDeliveryTimelinesReplayExactly(t *testing.T) {
	file := loadDeliveryVectors(t)
	for _, script := range file.Scripts {
		state := Queued
		for i, step := range script.Steps {
			event, ok := vectorEvents[step.Event]
			if !ok {
				t.Fatalf("%s step %d: unknown event %q", script.Name, i, step.Event)
			}
			state = Step(state, event)
			if vectorStateNames[state] != step.ExpectState || string(TickOf(state)) != step.ExpectTick {
				t.Errorf("%s step %d: %s/%s, vector %s/%s", script.Name, i,
					vectorStateNames[state], TickOf(state), step.ExpectState, step.ExpectTick)
			}
		}
	}
}

func TestTheFamilyOnlyReachesDeliveredOnAReceipt(t *testing.T) {
	file := loadDeliveryVectors(t)
	for _, script := range file.Scripts {
		previous := "queued"
		for i, step := range script.Steps {
			entered := step.ExpectState != previous &&
				(step.ExpectState == "delivered" || step.ExpectState == "read")
			if entered && step.Event != "delivery_receipt" && step.Event != "read_receipt" {
				t.Errorf("%s step %d: reached %s on %s (EDP WP-05 guardrail)",
					script.Name, i, step.ExpectState, step.Event)
			}
			previous = step.ExpectState
		}
	}
}

func TestCommittedReceiptPolicyMatches(t *testing.T) {
	file := loadDeliveryVectors(t)
	for _, c := range file.ReceiptPolicy {
		delivery, read := ReceiptsToSend(c.ReadReceiptsEnabled, c.Opened)
		if delivery != c.SendDelivery || read != c.SendRead {
			t.Errorf("policy enabled=%v opened=%v mismatch", c.ReadReceiptsEnabled, c.Opened)
		}
	}
}

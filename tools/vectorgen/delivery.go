// Lumos - tools/vectorgen / The delivery-state vector family.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-005, SRS-RMT-014, TRS-PAT-002, TRS-TST-001
// ADRs: ADR-002
// Threading: build-time
// Notes: Three sections. ticks pins the owner's WhatsApp-style glyph for
// every state, so both apps draw the same thing for the same truth. scripts
// are event timelines from QUEUED with every step's state and tick pinned -
// including the guardrail scenes: a transport success that shows one tick
// and never two, a late delivery receipt that cannot undo READ, and a
// recipient receipt that overrides a local FAILED. receipt_policy pins
// SRS-RMT-014. Every expectation runs against the reference before emission.

package main

import (
	"fmt"

	"github.com/dharabhishekorg/lumos/protocol/chat"
)

type deliveryStep struct {
	Event       string `json:"event"`
	ExpectState string `json:"expect_state"`
	ExpectTick  string `json:"expect_tick"`
}

type deliveryScript struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Steps       []deliveryStep `json:"steps"`
}

type deliveryTick struct {
	State string `json:"state"`
	Tick  string `json:"tick"`
}

type receiptPolicyCase struct {
	ReadReceiptsEnabled bool `json:"read_receipts_enabled"`
	Opened              bool `json:"opened"`
	SendDelivery        bool `json:"send_delivery"`
	SendRead            bool `json:"send_read"`
}

// deliveryVectorsFile is the emitted chat/delivery_states.json.
type deliveryVectorsFile struct {
	fileMeta
	Ticks         []deliveryTick      `json:"ticks"`
	Scripts       []deliveryScript    `json:"scripts"`
	ReceiptPolicy []receiptPolicyCase `json:"receipt_policy"`
}

func (f *deliveryVectorsFile) implementsIDs() []string { return f.Implements }

var deliveryStateNames = map[chat.State]string{
	chat.Queued: "queued", chat.Sending: "sending", chat.Sent: "sent",
	chat.Delivered: "delivered", chat.Read: "read", chat.Failed: "failed",
	chat.LocalOnlyWaiting: "local_only_waiting",
}

var deliveryEventNames = map[string]chat.Event{
	"send_started": chat.SendStarted, "transport_accepted": chat.TransportAccepted,
	"transport_retryable": chat.TransportRetryable, "attempts_exhausted": chat.AttemptsExhausted,
	"delivery_receipt": chat.DeliveryReceipt, "read_receipt": chat.ReadReceipt,
	"user_retry": chat.UserRetry, "local_path_unavailable": chat.LocalPathUnavailable,
	"local_path_available": chat.LocalPathAvailable,
}

func runDeliveryScript(s deliveryScript) error {
	state := chat.Queued
	for i, step := range s.Steps {
		event, ok := deliveryEventNames[step.Event]
		if !ok {
			return fmt.Errorf("delivery script %s step %d: unknown event %q", s.Name, i, step.Event)
		}
		state = chat.Step(state, event)
		if deliveryStateNames[state] != step.ExpectState || string(chat.TickOf(state)) != step.ExpectTick {
			return fmt.Errorf("delivery script %s step %d: reference %s/%s, vector %s/%s",
				s.Name, i, deliveryStateNames[state], chat.TickOf(state), step.ExpectState, step.ExpectTick)
		}
	}
	return nil
}

func deliverySteps(triples ...string) []deliveryStep {
	steps := make([]deliveryStep, 0, len(triples)/3)
	for i := 0; i+2 < len(triples); i += 3 {
		steps = append(steps, deliveryStep{Event: triples[i], ExpectState: triples[i+1], ExpectTick: triples[i+2]})
	}
	return steps
}

func deliveryScripts() []deliveryScript {
	return []deliveryScript{
		{Name: "happy_path_to_blue_ticks",
			Description: "Queued, sending, one tick on transport acceptance, two on the recipient's delivery receipt, blue on read.",
			Steps: deliverySteps(
				"send_started", "sending", "clock",
				"transport_accepted", "sent", "single",
				"delivery_receipt", "delivered", "double",
				"read_receipt", "read", "double_read")},
		{Name: "transport_success_never_shows_two_ticks",
			Description: "The relay accepting the upload is one tick; repeating it changes nothing. Only the recipient makes two.",
			Steps: deliverySteps(
				"send_started", "sending", "clock",
				"transport_accepted", "sent", "single",
				"transport_accepted", "sent", "single")},
		{Name: "receipts_never_move_backward",
			Description: "A read receipt that overtakes the delivery receipt leaves the message read.",
			Steps: deliverySteps(
				"send_started", "sending", "clock",
				"transport_accepted", "sent", "single",
				"read_receipt", "read", "double_read",
				"delivery_receipt", "read", "double_read")},
		{Name: "retry_then_fail_then_user_retry",
			Description: "A retryable failure requeues, an exhausted budget fails with the red mark, and the user's retry requeues.",
			Steps: deliverySteps(
				"send_started", "sending", "clock",
				"transport_retryable", "queued", "clock",
				"send_started", "sending", "clock",
				"attempts_exhausted", "failed", "failed",
				"user_retry", "queued", "clock")},
		{Name: "recipient_receipt_beats_local_failure",
			Description: "The first attempt actually landed; the recipient's receipt overrides the local failed state.",
			Steps: deliverySteps(
				"send_started", "sending", "clock",
				"attempts_exhausted", "failed", "failed",
				"delivery_receipt", "delivered", "double")},
		{Name: "receipt_overtakes_upload_response",
			Description: "The recipient acknowledged before our upload call returned; the late transport acceptance changes nothing.",
			Steps: deliverySteps(
				"send_started", "sending", "clock",
				"delivery_receipt", "delivered", "double",
				"transport_accepted", "delivered", "double")},
		{Name: "nearby_only_content_waits",
			Description: "Nearby-only content with no nearby path shows the nearby mark, then sends when a path appears.",
			Steps: deliverySteps(
				"local_path_unavailable", "local_only_waiting", "nearby",
				"send_started", "local_only_waiting", "nearby",
				"local_path_available", "queued", "clock",
				"send_started", "sending", "clock",
				"transport_accepted", "sent", "single")},
	}
}

// buildDeliveryVectors emits chat/delivery_states.json.
func buildDeliveryVectors(constantsHash string) (*deliveryVectorsFile, error) {
	file := &deliveryVectorsFile{
		fileMeta: fileMeta{
			SchemaVersion: 1, Generator: GeneratorName, ConstantsHash: constantsHash,
			Implements:    []string{"SRS-RMT-005", "SRS-RMT-014", "TRS-PAT-002"},
			KeyProvenance: KeyProvenance,
			Notes: "The SRS-RMT-005 outgoing-message delivery machine. ticks: the WhatsApp-style glyph " +
				"per state (owner ruling 2026-11-21): clock while queued or sending, single grey tick when " +
				"sent, double grey ticks when delivered, double blue ticks when read, a red failed mark, " +
				"and a nearby mark for content waiting for a nearby path. scripts: event timelines from " +
				"state queued; consumers compare state and tick after every step. Only delivery_receipt " +
				"and read_receipt ever reach delivered or read (the EDP guardrail: never derive delivered " +
				"from a transport); receipts never move a message backward; a recipient receipt overrides " +
				"a local failure. receipt_policy: SRS-RMT-014 - delivery receipts always go back, read " +
				"receipts only when enabled and opened.",
		},
	}
	for _, s := range chat.States() {
		file.Ticks = append(file.Ticks, deliveryTick{State: deliveryStateNames[s], Tick: string(chat.TickOf(s))})
	}
	file.Scripts = deliveryScripts()
	for _, s := range file.Scripts {
		if err := runDeliveryScript(s); err != nil {
			return nil, err
		}
	}
	for _, enabled := range []bool{true, false} {
		for _, opened := range []bool{true, false} {
			delivery, read := chat.ReceiptsToSend(enabled, opened)
			file.ReceiptPolicy = append(file.ReceiptPolicy, receiptPolicyCase{
				ReadReceiptsEnabled: enabled, Opened: opened, SendDelivery: delivery, SendRead: read})
		}
	}
	return file, nil
}

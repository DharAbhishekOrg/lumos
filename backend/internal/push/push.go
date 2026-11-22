// Lumos - backend/internal/push / The push dispatcher: a generic wake to a device's one route.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-BCK-006, SRS-RMT-006, TRS-BCK-002, SRS-IDN-008
// ADRs: -
// Threading: concurrent-safe
// Notes: A wake says "something is waiting for you" and nothing else: no
// sender, no content, no count, no conversation (TRS-BCK-006). The phone
// fetches its mailbox and shows generic text (SRS-RMT-006). The providers sit
// behind Sender, so FCM and APNs are adapters and tests use fakes. A provider
// that reports a token dead gets the route dropped - but only if that token
// is still the stored one, so a late report cannot erase a rotated token.
// Errors are built here from provider names and HTTP statuses only: a token,
// a device id or a payload can never reach a log through them (TRS-BCK-002).
// Notify runs the wake in the background so a sender's request never waits
// on a push provider; Close waits for wakes in flight at shutdown.
package push

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/dharabhishekorg/lumos/backend/internal/registry"
)

// ErrTokenGone means the provider says this token will never deliver again.
var ErrTokenGone = errors.New("push: token no longer valid")

// Sender delivers one generic wake to one token.
type Sender interface {
	Send(ctx context.Context, token string) error
}

// Routes is the registry's view the dispatcher needs.
type Routes interface {
	PushRoute(ctx context.Context, id registry.DeviceID) (registry.PushRoute, error)
	DropPushToken(ctx context.Context, id registry.DeviceID, token string) error
}

// wakeTimeout bounds one background wake, route lookup and provider call
// together. A wake later than this is not worth delivering: the phone will
// fetch its mailbox the next time it opens anyway.
const wakeTimeout = 30 * time.Second

// Dispatcher sends wakes through the configured providers.
type Dispatcher struct {
	routes  Routes
	senders map[registry.PushProvider]Sender
	onError func(error)
	wg      sync.WaitGroup
}

// NewDispatcher wires the providers that are configured; a provider absent
// from senders means devices on it are simply not woken. onError, if not nil,
// receives failures that are not a dead token; it never sees a token.
func NewDispatcher(routes Routes, senders map[registry.PushProvider]Sender, onError func(error)) *Dispatcher {
	return &Dispatcher{routes: routes, senders: senders, onError: onError}
}

// Wake sends one wake to the device now.
func (d *Dispatcher) Wake(ctx context.Context, id registry.DeviceID) error {
	route, err := d.routes.PushRoute(ctx, id)
	if errors.Is(err, registry.ErrNotFound) {
		return nil // no route: the phone fetches when it next opens
	}
	if err != nil {
		return err
	}
	sender, ok := d.senders[route.Provider]
	if !ok {
		return nil // that provider is not configured in this environment
	}
	err = sender.Send(ctx, route.Token)
	if errors.Is(err, ErrTokenGone) {
		return d.routes.DropPushToken(ctx, id, route.Token)
	}
	return err
}

// Notify wakes the device in the background.
func (d *Dispatcher) Notify(id registry.DeviceID) {
	d.wg.Add(1)
	go func() {
		defer d.wg.Done()
		ctx, cancel := context.WithTimeout(context.Background(), wakeTimeout)
		defer cancel()
		if err := d.Wake(ctx, id); err != nil && d.onError != nil {
			d.onError(err)
		}
	}()
}

// Close waits for wakes in flight.
func (d *Dispatcher) Close() { d.wg.Wait() }

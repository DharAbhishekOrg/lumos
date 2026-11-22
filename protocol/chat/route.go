// Lumos - protocol/chat / RELAY_ROUTE: one device's relay address for one matched peer.
// Author: dhar.abhishek.email@gmail.com
// Implements: SRS-RMT-001, SRS-RMT-016, SRS-MAT-005, TRS-PROTO-007
// ADRs: ADR-002
// Threading: any
// Notes: CR-005 (owner-accepted 2026-11-23). After a match each phone sends
// the other this payload over the authenticated, encrypted nearby session:
// its routing id, and the send capability it minted for that one peer. The
// peer presents the capability on every relay send; the relay stores only its
// SHA-256, so blocking or unmatching revokes exactly one peer (SRS-RMT-016).
// The class is post-match only (the consent gate) and never on the relay's
// allow-list, so no relay ever carries anyone's relay address.
//
// Layout, fixed under the TRS Sec. 19 AI delivery rule:
//
//	version u8 = 1 | routing_id[16] | capability[32]   (49 bytes exactly)
//
// An all-zero routing id or capability is refused: neither is ever minted
// from a CSPRNG, so an all-zero field means a broken or hostile sender.
package chat

const (
	// RoutingIDSize is a relay routing id's width (the registry's device id).
	RoutingIDSize = 16
	// CapabilitySize is a send capability's width.
	CapabilitySize = 32
	routeVersion   = 1
	// RouteSize is the exact encoded size of a RELAY_ROUTE payload.
	RouteSize = 1 + RoutingIDSize + CapabilitySize
)

// Route is one RELAY_ROUTE payload.
type Route struct {
	RoutingID  [RoutingIDSize]byte
	Capability [CapabilitySize]byte
}

// Encode renders version | routing_id | capability.
func (r Route) Encode() []byte {
	out := make([]byte, 0, RouteSize)
	out = append(out, routeVersion)
	out = append(out, r.RoutingID[:]...)
	return append(out, r.Capability[:]...)
}

// DecodeRoute parses a RELAY_ROUTE payload, refusing any other version, any
// other length and an all-zero field.
func DecodeRoute(b []byte) (Route, error) {
	if len(b) != RouteSize {
		return Route{}, malformed("relay route is not 49 bytes")
	}
	if b[0] != routeVersion {
		return Route{}, malformed("unknown relay route version")
	}
	var r Route
	copy(r.RoutingID[:], b[1:1+RoutingIDSize])
	copy(r.Capability[:], b[1+RoutingIDSize:])
	if r.RoutingID == ([RoutingIDSize]byte{}) || r.Capability == ([CapabilitySize]byte{}) {
		return Route{}, malformed("relay route field is all zero")
	}
	return r, nil
}

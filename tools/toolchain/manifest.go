// Lumos - tools/toolchain / Loader and invariants for the pinned toolchain manifest.
// Author: dhar.abhishek.email@gmail.com
// Implements: TRS-REP-003, TRS-REP-006
// ADRs: -
// Threading: any
// Notes: The manifest pins build toolchains only and is deliberately NOT a
// dependency lockfile; TRS-REP-003 locks dependency versions and checksums
// separately in WP-01 slice S7. Keeping the two apart stops a toolchain pin
// from being mistaken for supply-chain locking evidence.
package toolchain

import (
	"encoding/json"
	"fmt"
	"os"
)

// ManifestPath is the manifest location relative to the repository root.
const ManifestPath = "tools/toolchain/toolchain-manifest.json"

// Toolchain is one pinned language toolchain. Version is nil when the toolchain
// is genuinely unavailable on the authoring host, in which case Status and
// ExecutionSurface must say where it does run instead.
type Toolchain struct {
	Version          *string `json:"version"`
	Verified         string  `json:"verified"`
	License          string  `json:"license"`
	Install          string  `json:"install"`
	Why              string  `json:"why"`
	Status           string  `json:"status"`
	ExecutionSurface string  `json:"execution_surface"`
}

// Manifest is the parsed toolchain manifest.
type Manifest struct {
	SchemaVersion int                  `json:"schema_version"`
	WorkPackage   string               `json:"work_package"`
	Slice         string               `json:"slice"`
	Implements    []string             `json:"implements"`
	Toolchains    map[string]Toolchain `json:"toolchains"`
	Supporting    map[string]struct {
		Version   *string `json:"version"`
		Scope     string  `json:"scope"`
		Status    string  `json:"status"`
		Reachable *bool   `json:"reachable"`
	} `json:"supporting"`
}

// Load reads and parses the manifest at path.
func Load(path string) (*Manifest, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read toolchain manifest: %w", err)
	}
	var m Manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("parse toolchain manifest: %w", err)
	}
	return &m, nil
}

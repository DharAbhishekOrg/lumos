# Bootstrap (TRS-REP-006)

Fresh-machine setup for Lumos. Two scripts, one contract.

| Host | Script |
| --- | --- |
| Linux, macOS | `tools/bootstrap/bootstrap.sh` |
| Windows | `tools/bootstrap/bootstrap.ps1` |

```bash
# report what is present, missing or drifted (default; changes nothing)
./tools/bootstrap/bootstrap.sh

# print the exact install commands without running them
./tools/bootstrap/bootstrap.sh --print

# actually install what is missing (brew on macOS, apt on Debian/Ubuntu)
./tools/bootstrap/bootstrap.sh --install
```

```powershell
.\tools\bootstrap\bootstrap.ps1            # verify
.\tools\bootstrap\bootstrap.ps1 -Print     # print install commands
.\tools\bootstrap\bootstrap.ps1 -Install   # install via winget
```

## Three decisions worth knowing

**Verify is the default; install is opt-in.** A script that installs software
the moment it runs is a bad citizen on a machine that is not yours. You see the
commands before anything happens.

**No version numbers live in these scripts.** They read
`tools/toolchain/toolchain-manifest.json`, which is the single pinned record. A
second copy in a shell script drifts silently, so `bootstrap_test.go` fails the
build if a pinned version literal reappears in either script.

**Major/minor mismatch fails; patch drift warns.** A different Go minor or Java
major changes language semantics and build output — that is a real problem. A
patch difference is usually your package manager moving under you, and pinning
every developer machine to the patch is not achievable without vendoring a
toolchain. `--strict` / `-Strict` promotes the warning to a failure for release
work.

## What the scripts do not install

**Gradle.** The committed wrapper pins the distribution by SHA-256. A system
Gradle would bypass that pin (TRS-REP-003), so the script checks for `gradlew`
and tells you not to install Gradle.

**Swift and Xcode.** Reported, never installed. The iOS shell and
`LumosProtocolConformance` build on macOS; on Linux and Windows the script says
so plainly rather than skipping the line. WP-01's Swift evidence comes from the
`macos-latest` CI job.

**Android `cmdline-tools`.** Flagged when absent, with the exact steps, but not
downloaded: it comes from a licence-gated Google endpoint and accepting an SDK
licence is the developer's act, not the script's. This gap was carried from
slice S0 as an explicit S7 preflight item — the authoring host has the SDK
platforms but no `cmdline-tools`, which is why headless licence acceptance
needed solving in CI (`android-actions/setup-android`).

## Sample environment

`sample-env/lumos.env.sample` is the deterministic sample environment
TRS-REP-006 requires. Copy it to `.env.local` (git-ignored):

```bash
cp tools/bootstrap/sample-env/lumos.env.sample .env.local
```

Every value in it is a fixed placeholder. No value is derived from the host,
the clock or the network, so two developers who follow this file get identical
behaviour. It deliberately contains **no** database URL, push certificate,
signing password or API token — not even a dummy one. A placeholder shaped like
a secret teaches the wrong habit and gives a secret scanner nothing to
distinguish from the real thing. Real credentials live in owner-controlled
KMS/HSM (TRS-REP-005, gate G-05).

## After bootstrap

```bash
go test ./...
./gradlew :protocol-conformance:test
(cd admin-console && npm ci && npm test)
swift test --parallel        # macOS only
```

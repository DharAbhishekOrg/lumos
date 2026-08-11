#!/usr/bin/env bash
# Lumos - tools/bootstrap / Fresh-machine bootstrap for Linux and macOS.
# Author: dhar.abhishek.email@gmail.com
# Implements: TRS-REP-006, TRS-CICD-008
# ADRs: -
# Threading: n/a
# Notes: Versions are NOT written here. They are read from
# tools/toolchain/toolchain-manifest.json, which is the single pinned record;
# a second copy in a shell script is a second source of truth that drifts
# silently. bootstrap_test.go fails the build if a pinned version literal
# reappears in this file.
#
# Default mode is VERIFY, not install. A bootstrap script that silently
# installs software the moment it is run is a bad citizen on a machine that is
# not yours; --install is explicit and prints each command before running it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="$REPO_ROOT/tools/toolchain/toolchain-manifest.json"

MODE="verify"
STRICT="no"
for arg in "$@"; do
  case "$arg" in
    --install) MODE="install" ;;
    --print) MODE="print" ;;
    --verify) MODE="verify" ;;
    --strict) STRICT="yes" ;;
    -h|--help)
      cat <<'USAGE'
usage: tools/bootstrap/bootstrap.sh [--verify|--print|--install] [--strict]

  --verify   (default) report what is present, missing or drifted; exit 1 on a problem
  --print    print the exact install commands and exit 0, changing nothing
  --install  run the install commands (brew on macOS, apt on Debian/Ubuntu)
  --strict   treat a patch-level version drift as a failure, not a warning

The pinned versions come from tools/toolchain/toolchain-manifest.json.
USAGE
      exit 0
      ;;
    *) echo "unknown argument: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# --- manifest reading -------------------------------------------------------
# jq first, python3 second. Both are present on every GitHub runner and on a
# developer machine that has done any other work; if neither exists we say so
# rather than hand-rolling a JSON parser in sh.
read_manifest() { # $1 = dotted path, e.g. toolchains.go.version
  if command -v jq >/dev/null 2>&1; then
    jq -r ".$1 // empty" "$MANIFEST"
  elif command -v python3 >/dev/null 2>&1; then
    python3 - "$MANIFEST" "$1" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1]))
for key in sys.argv[2].split("."):
    doc = (doc or {}).get(key) if isinstance(doc, dict) else None
print("" if doc is None else doc)
PY
  else
    echo "bootstrap: needs jq or python3 to read the toolchain manifest." >&2
    echo "  macOS:  brew install jq" >&2
    echo "  Debian: sudo apt-get install -y jq" >&2
    exit 3
  fi
}

case "$(uname -s)" in
  Darwin) PLATFORM="macos" ;;
  Linux) PLATFORM="linux" ;;
  *) echo "bootstrap: unsupported platform $(uname -s). Use bootstrap.ps1 on Windows." >&2; exit 2 ;;
esac

PROBLEMS=0
WARNINGS=0

note() { printf '  %s\n' "$*"; }
ok() { printf 'OK       %-10s %s\n' "$1" "$2"; }
bad() { printf 'MISSING  %-10s %s\n' "$1" "$2"; PROBLEMS=$((PROBLEMS + 1)); }
drift() { printf 'DRIFT    %-10s %s\n' "$1" "$2"; WARNINGS=$((WARNINGS + 1)); }
fail() { printf 'FAIL     %-10s %s\n' "$1" "$2"; PROBLEMS=$((PROBLEMS + 1)); }

# install_hint prints, and in --install mode runs, the platform command.
install_hint() { # $1 = tool, $2 = brew formula, $3 = apt package
  local cmd
  if [ "$PLATFORM" = "macos" ]; then
    cmd="brew install $2"
  else
    cmd="sudo apt-get install -y $3"
  fi
  note "install: $cmd"
  if [ "$MODE" = "install" ]; then
    note "running: $cmd"
    eval "$cmd"
  fi
}

# check_version compares an observed version against the pinned one.
# A major/minor difference is a hard failure: it changes language semantics and
# build output. A patch difference is a warning by default, because CI images
# and package managers move patch levels under you and pinning to the patch on
# every developer machine is not achievable without vendoring a toolchain.
check_version() { # $1 = tool, $2 = observed, $3 = pinned
  local tool="$1" observed="$2" pinned="$3"
  local o_mm p_mm
  o_mm="$(printf '%s' "$observed" | cut -d. -f1,2)"
  p_mm="$(printf '%s' "$pinned" | cut -d. -f1,2)"
  if [ "$o_mm" != "$p_mm" ]; then
    fail "$tool" "version $observed, manifest pins $pinned (major/minor mismatch)"
    return
  fi
  if [ "$observed" != "$pinned" ]; then
    drift "$tool" "version $observed, manifest pins $pinned (patch drift)"
    return
  fi
  ok "$tool" "$observed"
}

echo "Lumos bootstrap - platform: $PLATFORM, mode: $MODE"
echo "manifest: tools/toolchain/toolchain-manifest.json"
echo

# --- Go ---------------------------------------------------------------------
GO_PIN="$(read_manifest toolchains.go.version)"
if command -v go >/dev/null 2>&1; then
  check_version go "$(go version | sed -E 's/.*go([0-9.]+).*/\1/')" "$GO_PIN"
else
  bad go "required $GO_PIN - builds every tool under tools/ and the backend"
  install_hint go "go" "golang-go"
fi

# --- JDK --------------------------------------------------------------------
JDK_PIN="$(read_manifest toolchains.jdk.version)"
JDK_PIN_NUM="${JDK_PIN%%+*}"
if command -v javac >/dev/null 2>&1; then
  check_version jdk "$(javac -version 2>&1 | sed -E 's/javac ([0-9.]+).*/\1/')" "$JDK_PIN_NUM"
else
  bad jdk "required $JDK_PIN (Eclipse Temurin) - drives :protocol-conformance"
  install_hint jdk "--cask temurin@17" "openjdk-17-jdk"
fi

# --- Node -------------------------------------------------------------------
NODE_PIN="$(read_manifest toolchains.node.version)"
if command -v node >/dev/null 2>&1; then
  check_version node "$(node --version | tr -d 'v')" "$NODE_PIN"
else
  bad node "required $NODE_PIN - admin-console build and tests"
  install_hint node "node@24" "nodejs"
fi

# --- Swift (macOS only) -----------------------------------------------------
if [ "$PLATFORM" = "macos" ]; then
  if command -v swift >/dev/null 2>&1; then
    ok swift "$(swift --version 2>&1 | head -1)"
  else
    bad swift "required for the iOS shell and LumosProtocolConformance"
    note "install: xcode-select --install, then install Xcode from the App Store"
  fi
else
  note "swift: skipped - the iOS targets build on macOS only (see the manifest's execution_surface)"
fi

# --- git --------------------------------------------------------------------
if command -v git >/dev/null 2>&1; then
  ok git "$(git --version | sed -E 's/git version //')"
else
  bad git "required to check out and stamp the repository"
  install_hint git "git" "git"
fi

# --- Gradle -----------------------------------------------------------------
# Deliberately NOT installed. The committed wrapper pins the distribution by
# SHA-256; a system Gradle would bypass that pin (TRS-REP-003).
if [ -x "$REPO_ROOT/gradlew" ]; then
  ok gradle "committed wrapper (SHA-256 pinned); do not install a system Gradle"
else
  fail gradle "gradlew is missing or not executable"
fi

# --- Android SDK ------------------------------------------------------------
# cmdline-tools is what accepts licences and installs platforms headlessly. It
# was absent on the authoring host; that gap is why this check exists.
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [ "$PLATFORM" = "macos" ]; then
  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
fi
if [ -d "$SDK_ROOT/platforms/android-36" ]; then
  ok android "SDK platform 36 present at $SDK_ROOT"
else
  bad android "SDK platform 36 absent (TRS Sec. 5.3 baseline) at $SDK_ROOT"
  note "install: sdkmanager 'platforms;android-36' 'build-tools;36.1.0' 'platform-tools'"
fi
if [ -d "$SDK_ROOT/cmdline-tools" ]; then
  ok android "cmdline-tools present"
else
  bad android "cmdline-tools absent - licences cannot be accepted headlessly"
  note "install: download commandlinetools from developer.android.com, unpack to"
  note "         \$ANDROID_SDK_ROOT/cmdline-tools/latest, then: sdkmanager --licenses"
fi

# --- sample environment -----------------------------------------------------
SAMPLE="$REPO_ROOT/tools/bootstrap/sample-env/lumos.env.sample"
if [ -f "$SAMPLE" ]; then
  ok env "deterministic sample environment at tools/bootstrap/sample-env/"
  note "copy it: cp tools/bootstrap/sample-env/lumos.env.sample .env.local"
  note "it contains placeholders only - real secrets never live in this repository (TRS-REP-005)"
else
  fail env "the sample environment is missing"
fi

echo
if [ "$MODE" = "print" ]; then
  echo "print mode: nothing was changed."
  exit 0
fi
if [ "$PROBLEMS" -gt 0 ]; then
  echo "$PROBLEMS problem(s), $WARNINGS warning(s). Not ready to build."
  exit 1
fi
if [ "$WARNINGS" -gt 0 ] && [ "$STRICT" = "yes" ]; then
  echo "$WARNINGS warning(s) and --strict was given. Not ready to build."
  exit 1
fi
echo "Ready. $WARNINGS warning(s)."
echo "Next: go test ./... && ./gradlew :protocol-conformance:test"

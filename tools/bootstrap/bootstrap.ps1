# Lumos - tools/bootstrap / Fresh-machine bootstrap for Windows.
# Author: dhar.abhishek.email@gmail.com
# Implements: TRS-REP-006, TRS-CICD-008
# ADRs: -
# Threading: n/a
# Notes: The Windows counterpart of bootstrap.sh, with the same contract:
# versions come from tools/toolchain/toolchain-manifest.json and are never
# written here, VERIFY is the default and --Install is explicit.
#
# Windows is an AUTHORING platform for Lumos, not a target one. The iOS and
# Swift toolchains are absent by design and are reported as such rather than
# quietly skipped; TRS-CICD-008 requires macOS for the apps and Linux for
# backend/admin, and no amount of local tooling changes that.

[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Print,
    [switch]$Strict
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestPath = Join-Path $repoRoot 'tools\toolchain\toolchain-manifest.json'
$manifest = Get-Content -Raw -Path $manifestPath | ConvertFrom-Json

$mode = 'verify'
if ($Print) { $mode = 'print' }
if ($Install) { $mode = 'install' }

$script:problems = 0
$script:warnings = 0

function Write-Note($text) { Write-Host ("  " + $text) }
function Write-Ok($tool, $text) { Write-Host ("OK       {0,-10} {1}" -f $tool, $text) }
function Write-Missing($tool, $text) {
    Write-Host ("MISSING  {0,-10} {1}" -f $tool, $text)
    $script:problems++
}
function Write-Drift($tool, $text) {
    Write-Host ("DRIFT    {0,-10} {1}" -f $tool, $text)
    $script:warnings++
}
function Write-Fail($tool, $text) {
    Write-Host ("FAIL     {0,-10} {1}" -f $tool, $text)
    $script:problems++
}

# Compare an observed version with the pinned one. Major/minor difference is a
# hard failure; patch difference is a warning unless -Strict. Same rule as the
# POSIX script, and bootstrap_test.go asserts the two agree.
function Test-Version($tool, $observed, $pinned) {
    $o = ($observed -split '\.')[0..1] -join '.'
    $p = ($pinned -split '\.')[0..1] -join '.'
    if ($o -ne $p) {
        Write-Fail $tool "version $observed, manifest pins $pinned (major/minor mismatch)"
        return
    }
    if ($observed -ne $pinned) {
        Write-Drift $tool "version $observed, manifest pins $pinned (patch drift)"
        return
    }
    Write-Ok $tool $observed
}

function Invoke-InstallHint($command) {
    Write-Note "install: $command"
    if ($mode -eq 'install') {
        Write-Note "running: $command"
        Invoke-Expression $command
    }
}

function Get-ToolPath($name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { return $null }
    return $cmd.Source
}

Write-Host "Lumos bootstrap - platform: windows, mode: $mode"
Write-Host "manifest: tools/toolchain/toolchain-manifest.json"
Write-Host ""

# --- Go ---------------------------------------------------------------------
$goPin = $manifest.toolchains.go.version
if (Get-ToolPath 'go') {
    $observed = (& go version) -replace '.*go([0-9.]+).*', '$1'
    Test-Version 'go' $observed $goPin
}
else {
    Write-Missing 'go' "required $goPin - builds every tool under tools/ and the backend"
    Invoke-InstallHint $manifest.toolchains.go.install
}

# --- JDK --------------------------------------------------------------------
$jdkPin = ($manifest.toolchains.jdk.version -split '\+')[0]
if (Get-ToolPath 'javac') {
    $observed = (& javac -version 2>&1) -replace 'javac ([0-9.]+).*', '$1'
    Test-Version 'jdk' $observed $jdkPin
}
else {
    Write-Missing 'jdk' "required $($manifest.toolchains.jdk.version) (Eclipse Temurin) - drives :protocol-conformance"
    Invoke-InstallHint $manifest.toolchains.jdk.install
}

# --- Node -------------------------------------------------------------------
$nodePin = $manifest.toolchains.node.version
if (Get-ToolPath 'node') {
    $observed = (& node --version).TrimStart('v')
    Test-Version 'node' $observed $nodePin
}
else {
    Write-Missing 'node' "required $nodePin - admin-console build and tests"
    Invoke-InstallHint $manifest.toolchains.node.install
}

# --- git --------------------------------------------------------------------
if (Get-ToolPath 'git') {
    Write-Ok 'git' ((& git --version) -replace 'git version ', '')
}
else {
    Write-Missing 'git' 'required to check out and stamp the repository'
    Invoke-InstallHint 'winget install --id Git.Git --exact'
}

# --- Swift ------------------------------------------------------------------
# Reported, never installed. Recording this honestly is the point: WP-01's
# Swift evidence comes from the macos-latest CI job, not from this host.
Write-Note 'swift: not available on Windows. The iOS shell and LumosProtocolConformance'
Write-Note '       build on the macos-latest CI job (see manifest execution_surface).'

# --- Gradle -----------------------------------------------------------------
# Never installed system-wide: the committed wrapper pins the distribution by
# SHA-256 and a system Gradle would bypass that pin (TRS-REP-003).
if (Test-Path (Join-Path $repoRoot 'gradlew.bat')) {
    Write-Ok 'gradle' 'committed wrapper (SHA-256 pinned); do not install a system Gradle'
}
else {
    Write-Fail 'gradle' 'gradlew.bat is missing'
}

# --- Android SDK ------------------------------------------------------------
$sdkRoot = $env:ANDROID_SDK_ROOT
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_HOME }
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

if (Test-Path (Join-Path $sdkRoot 'platforms\android-36')) {
    Write-Ok 'android' "SDK platform 36 present at $sdkRoot"
}
else {
    Write-Missing 'android' "SDK platform 36 absent (TRS Sec. 5.3 baseline) at $sdkRoot"
    Write-Note "install: sdkmanager 'platforms;android-36' 'build-tools;36.1.0' 'platform-tools'"
}
if (Test-Path (Join-Path $sdkRoot 'cmdline-tools')) {
    Write-Ok 'android' 'cmdline-tools present'
}
else {
    Write-Missing 'android' 'cmdline-tools absent - licences cannot be accepted headlessly'
    Write-Note 'install: download commandlinetools from developer.android.com, unpack to'
    Write-Note '         $env:ANDROID_SDK_ROOT\cmdline-tools\latest, then: sdkmanager --licenses'
}

# --- sample environment -----------------------------------------------------
$sample = Join-Path $repoRoot 'tools\bootstrap\sample-env\lumos.env.sample'
if (Test-Path $sample) {
    Write-Ok 'env' 'deterministic sample environment at tools/bootstrap/sample-env/'
    Write-Note 'copy it: Copy-Item tools\bootstrap\sample-env\lumos.env.sample .env.local'
    Write-Note 'it contains placeholders only - real secrets never live in this repository (TRS-REP-005)'
}
else {
    Write-Fail 'env' 'the sample environment is missing'
}

Write-Host ""
if ($mode -eq 'print') {
    Write-Host 'print mode: nothing was changed.'
    exit 0
}
if ($script:problems -gt 0) {
    Write-Host "$($script:problems) problem(s), $($script:warnings) warning(s). Not ready to build."
    exit 1
}
if ($script:warnings -gt 0 -and $Strict) {
    Write-Host "$($script:warnings) warning(s) and -Strict was given. Not ready to build."
    exit 1
}
Write-Host "Ready. $($script:warnings) warning(s)."
Write-Host 'Next: go test ./... ; .\gradlew.bat :protocol-conformance:test'

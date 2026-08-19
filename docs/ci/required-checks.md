<!--
  Lumos - docs/ci / The check names the repository ruleset must require.
  Author: dhar.abhishek.email@gmail.com
  Implements: TRS-CICD-001, TRS-CICD-007
  ADRs: -
  Threading: n/a
  Notes: This file is the CONTRACT between two things that live in different
  places and cannot see each other: the `name:` a workflow job reports, and the
  string a GitHub ruleset requires. They drifted once already - the ruleset was
  configured with names that no job reported, so the entries were expected for
  ever and never satisfied - and nothing in either place could have noticed.
  tools/cicheck now fails the build if this table and the workflows disagree,
  so the workflow side of the contract cannot move silently. The ruleset side
  is owner-held; Sec. 4 is how to verify it.
-->

# Required checks

## 1. Why this file exists

A GitHub ruleset requires a **check name**, which for GitHub Actions is the
job's `name:` field — not the job id, not the workflow name, and not the
`workflow / job` form the pull-request UI displays. Get it wrong in either
direction and the failure is silent in the direction that matters:

- **A required name no job reports** is expected for ever. Every pull request
  is blocked on a check that will never arrive. Loud, and therefore harmless.
- **A job whose name nobody requires** is not a gate at all. It can be red on a
  mergeable pull request. Quiet, and therefore the dangerous one.

The second is the one this file exists to prevent, because it does not announce
itself. The check names below are the complete set the two workflows report.

## 2. The contract

Two rules, both enforced by `tools/cicheck`:

1. **Every job's check name appears in the table below, and every name in the
   table belongs to a job.** Renaming a job without editing this file fails the
   build.
2. **Check names are unique across workflows.** Two jobs reporting the same
   name would make a ruleset entry ambiguous about which one satisfied it.

And a rule about the names themselves, which is judgement rather than
automation: **a required check's name must not describe how the job works.**
`swift conformance + iOS shell (macOS)` was accurate and was also three facts
that change — the second target, the runner OS, the punctuation. Every one of
those is a reason to edit a name, and every edit to a required name silently
un-requires it until the ruleset is edited too. Names of required checks say
*what is being gated*, and the detail lives in the workflow's comments.

## 3. The checks

`conformance` is `.github/workflows/conformance.yml`; `pipeline` is
`.github/workflows/pipeline.yml`.

### Required — the ruleset must list exactly these

| Check name | Workflow | Job | Required | What it gates |
| --- | --- | --- | --- | --- |
| `go conformance` | conformance | `go` | yes | gofmt, vet, the constants gate, the vector gate, the whole Go suite |
| `java conformance` | conformance | `java` | yes | the pure-JVM conformance runner (TRS Sec. 18.4) |
| `swift conformance` | conformance | `swift` | yes | SwiftLint, the zero-warning build, both Swift test targets, the iOS build |
| `android unit tests` | conformance | `android` | yes | the four-variant unit suites (TRS-REP-004) |
| `android instrumented tests` | conformance | `android-instrumented` | yes | Room and the recovery record on a real Android runtime |
| `admin console tests` | conformance | `ts` | yes | the admin console suite |
| `traceability gate` | conformance | `trace` | yes | TRS Sec. 19.3: every `tested` row rests on a test that ran in this run |
| `conformance gate` | conformance | `conformance-gate` | yes | every job above, including the two the path filter may skip |
| `pipeline gate` | pipeline | `pipeline-gate` | yes | every job in the pipeline workflow |

### Reported but not required

These report on every pull request and are covered by `pipeline gate`, which is
required. Requiring them individually as well is harmless and redundant.

| Check name | Workflow | Job | Required | What it gates |
| --- | --- | --- | --- | --- |
| `path filter` | conformance | `changes` | no | decides whether Swift and the emulator can be skipped |
| `lint (headers, constants, architecture, layout, eslint)` | pipeline | `lint` | no | file headers, ADR-004 constants, layering, layout, eslint, golangci-lint |
| `static analysis - Checkstyle + SpotBugs` | pipeline | `java-static-analysis` | no | TRS-CODE-006, Java half |
| `secret scan (full history)` | pipeline | `secret-scan` | no | TRS-REP-005, gitleaks over the full history |
| `dependency locks and checksums` | pipeline | `dependencies` | no | lockfile exactness, `go mod verify`, the wrapper checksum |
| `SBOM` | pipeline | `sbom` | no | the committed SBOM is current |
| `shell build - Go backend` | pipeline | `shell-backend` | no | the backend shell compiles |
| `shell build - TypeScript admin console` | pipeline | `shell-admin` | no | the admin shell compiles |
| `shell build - Android (Java 17, API 36)` | pipeline | `shell-android` | no | the Android shell assembles and Android Lint passes |

## 4. Configuring and verifying the ruleset

The required set above is nine names. Read the ruleset back and compare:

```sh
gh api repos/DharAbhishekOrg/lumos/rulesets \
  --jq '.[] | select(.name=="main") | .id'

gh api repos/DharAbhishekOrg/lumos/rulesets/<id> \
  --jq '.rules[] | select(.type=="required_status_checks")
        | .parameters.required_status_checks[].context' | sort
```

Every line that comes back must appear in the **Required** table above,
spelled identically — leading and trailing spaces included, which the ruleset
UI does not trim for you.

`strict_required_status_checks_policy` is on, which means a branch must be up
to date with `main` before merging. Keep it on: the traceability gate compares
the matrix against `origin/main`, and a stale branch would be judged against a
base that has moved.

## 5. Skipped is not the same as passed, except when it is

This is the behaviour the whole arrangement turns on, and it is worth stating
exactly because it is counter-intuitive:

| Situation | What GitHub reports | What a ruleset does with it |
| --- | --- | --- |
| Job runs and passes | success | satisfied |
| Job runs and fails | failure | blocks |
| Job skipped by a job-level `if:` | **skipped** | **satisfied** |
| Whole workflow filtered out by `on: paths` | **nothing at all** | **expected for ever — blocks** |

Two consequences, both enforced by `tools/cicheck`:

- **Neither workflow may declare `paths:` or `paths-ignore:` under `on:`.**
  Filtering there makes a required check stop reporting, which blocks every
  pull request that does not touch those paths. Path filtering belongs in a
  job-level `if:`, where the skip still posts a check run.
- **A required job's `if:` may test the path filter's outputs and nothing
  else.** In particular it may not test another job's `result`: a required
  check that skips when an upstream job fails is a required check that
  disappears at the moment it was supposed to speak. The traceability gate did
  exactly that until the WP-02 closeout — it skipped whenever Go, Java, the
  Android unit tests or the admin console failed, and a skipped required check
  counts as satisfied. It now runs, asserts those results in its first step,
  and fails.

`swift conformance` and `android instrumented tests` are the only two jobs
allowed to skip, and only for the one reason the path filter encodes: the diff
cannot change what they assert.

## 6. When you add a job

1. Give it a `name:` that says what it gates, not how.
2. Add a row here, in the right table.
3. Add it to `pipeline gate` or `conformance gate` — a job in neither is a job
   nothing enforces.
4. Run `go test ./tools/cicheck/`.
5. If it is required, add the name to the ruleset **before** merging, or the
   first pull request after the merge is blocked on a name nobody reports.

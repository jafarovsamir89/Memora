# Memora Automated Acceptance Lab

The acceptance runner is a repeatable, synthetic-data-only entry point for the
Android app. It is restricted to `ui/redesign-v1` and an Android Emulator.
Physical devices are never selected. It does not call Gemini, Firebase AI, or
any paid API, and it does not wipe app data.

## Commands

Run from the repository root:

```sh
python3 tools/run_memora_acceptance.py preflight --serial emulator-5556
python3 tools/run_memora_acceptance.py quick --serial emulator-5556
python3 tools/run_memora_acceptance.py full --serial emulator-5556
python3 tools/run_memora_acceptance.py device-smoke --serial emulator-5556
python3 tools/run_memora_acceptance.py device-lineage --serial emulator-5556 --synthetic-user-id 10 --seed-synthetic-profile
python3 tools/run_memora_acceptance.py volume-probe --serial emulator-5556 --synthetic-user-id 11
```

- `preflight` verifies the branch, host/emulator free disk, and the selected
  target's Android system properties. It is read-only apart from the report.
- `quick` runs the current focused agent, finance, task, memory, routing,
  encrypted-store, and deterministic synthetic-profile JVM tests; it does not
  install or launch the app. The profile fixture is test-source-only, uses an
  in-memory Room database plus dedicated temporary encrypted files, and refuses
  to seed a non-empty database. It covers fixed object IDs, same-name identities,
  confirmed aliases, unconfirmed alias suggestions kept out of persistence,
  linked BMW case/finance/project/note/task/calendar records, exact correction
  balances, past/future dates, AZ/RU/mixed dialogue, source-backed memory,
  Inbox, and an editable table. Independent database runs must yield the same
  snapshot. Fake-provider journeys exercise the existing orchestrator and
  tools: a 300 AZN BMW obligation, 100 AZN repayment, correction to 120 AZN,
  exact 180 AZN remaining readback and case-source links; ambiguous same-name
  Ruslan is clarified without a write; and a scoped AZ task/date, case, ledger,
  memory and original Russian conversation source are read back by stable IDs.
  Unified memory search includes tasks through bounded owner-confirmed canonical
  person links or existing lexical/date matches; unconfirmed alias suggestions
  never become identity links. A synthetic failure matrix covers pre-write
  timeout/503/offline, post-write 503, repeated semantic receipt calls with
  different provider IDs, and journal recovery after a simulated interruption
  following a ledger commit. These are fake-provider/local-store tests, not live
  network or OS process-kill experiments. Duplicate suppression is keyed by the
  prepared payload digest and scoped to one agent run; a fresh user turn may
  intentionally repeat an action.
- `full` runs the complete unit suite, debug and AndroidTest builds, test Kotlin
  compile, lint, and the existing 31-state component visual comparison. Gradle
  receives `ANDROID_SERIAL` for the already-verified emulator.
- `device-smoke` assembles debug, installs with `adb -s SERIAL install -r`,
  opens `MainActivity`, saves one real emulator screenshot, and checks package
  version and `firstInstallTime`. It records ActivityManager startup time and
  waits 15 seconds after launch so the frame is not the brief Room/profile
  startup placeholder. It never runs `pm clear` or uninstalls.

For a populated-profile version update, use an isolated Android Emulator user
and the test-only `LocalInstallLineageProbe` through
`ai.memora.app.test/ai.memora.app.GeminiSyntheticProbe`. The probe refuses to
seed unless the profile and target stores are empty and the install marker is
absent. Never use it against a physical device or the owner's existing emulator
user. One-time setup on the verified emulator: create a secondary user named
`MemoraReliabilityLab`, install the already-present app and AndroidTest packages
for that user with `pm install-existing --user <id>`, start and switch to that
user, then run `device-lineage ... --seed-synthetic-profile`. Record the ID
returned by Android; it must be non-zero and the runner checks the exact user
name and current-user ID before any profile operation. The seed flag is
one-time; omit it on later runs, which first require the existing sentinel
profile to verify successfully.

For a clean-first-run check, use a separate fresh secondary user: the
test-only `prepare-notification-race` phase creates the empty Room file through
notification reconciliation and asserts that the marker is still absent;
launch `MainActivity`, visually confirm onboarding, then run
`localInstallLineagePhase=verify-new-install`. It verifies the per-user marker
is `new` and the local profile remains empty. Do not reuse a seeded user or
clear an existing user's data for this check.

The lineage mode builds a debug APK with a strictly higher temporary version
using both `-PmemoraAcceptanceVersionCode=<higher integer>` and
`-PmemoraAcceptanceVersionName=<test label>`; these overrides are rejected for
other Gradle tasks and do not change the checked-in version. It installs only
for the named synthetic user via `adb -s SERIAL install --user <id> -r`,
launches, verifies exact sentinel readback, force-stops/restarts, and performs a
rotation/recreation check while restoring the user's original rotation
settings. Reports and real screenshots stay under ignored `app/build` output.
Review screenshots manually for onboarding/UI state; the runner does not
claim visual acceptance from screenshot capture alone. Keep the seeded user
isolated; do not clear or uninstall the owner's app profile.

The bounded `volume-probe` mode is restricted to the separately named
`MemoraCleanInstallLab` Android user (the confirmed ID on this emulator is 11).
It adds deterministic 100-row and then 1,000-row fixtures for Finance, Cases,
Notes and an editable Table to that synthetic profile, refusing unexpected
records or a fixture that would need to shrink. It verifies each row and Case /
Note revision before and after insertion, and verifies stable IDs plus a
deterministic digest on repeat runs. It records device-local Room/repository
p50/p95 timings and instantaneous process-memory snapshots. Fast paths report
microseconds so sub-millisecond samples are not rounded to zero. These are
warm local reads; they do not represent UI-frame latency, cold-cache behavior,
peak memory or ANR risk. The initial campaign uses both sizes; a later 1,000-row
retest can use `--volume-count 1000` because fixtures are monotonic and stay in
that isolated synthetic profile. Tasks, people, long conversations, startup,
screen rendering, cold reads, UI search and ANR remain unmeasured.

Each run writes `apps/android/app/build/acceptance-lab/<UTC run id>/report.json`.
The report includes mode/status, branch/HEAD, emulator properties, disk space,
step exit codes and durations, JUnit totals when available, ActivityManager
startup timing, package lineage, and screenshot path. Build output is kept in
memory and only a bounded, credential-redacted tail is included. These results
live under ignored build output and must not be committed.

`quick` and `full` use a generated Gradle init script that invalidates only the
`testDebugUnitTest` task's up-to-date result. This refreshes JUnit XML every
time while leaving compilation and unrelated build tasks incremental. A run
with zero fresh JUnit tests is reported as `FAIL`, even if Gradle exits
successfully.

## Safety behavior

The runner reads `adb devices -l` once to find connected targets, extracts only
emulator serials, then pins every later ADB command to one serial using
`adb -s`. Physical serials/descriptors are not retained in reports. A
candidate must report `ro.kernel.qemu=1` and an emulator-specific hardware,
characteristics, or build fingerprint marker. With multiple emulators, pass
`--serial`; physical-device serials are rejected. The host and emulator each
need at least 2 GiB and 1 GiB free by default before Gradle runs. Thresholds can
be raised or lowered only explicitly with the corresponding CLI options.

There is intentionally no app-profile reset or normal-install data-seeding
command. The host test fixture seeds only isolated in-memory/test stores and
must never be wired into the production app path. Fake-provider journey tests
exercise runtime tools and exact readbacks in those isolated stores; they do
not cover persistent emulator UI routes, Back-stack restoration, process
restart or populated-install retention. Any future device profile fixture
must use a clearly named synthetic dataset and explicit emulator-only
instrumentation. The current
`LocalInstallLineageProbe` is a test-only migration/onboarding probe, not yet a
general-purpose synthetic profile seeder.

The current identity model persists only owner-confirmed aliases. Pending alias
suggestions in the synthetic acceptance manifest are deliberately not database
rows; no pending-alias product model or schema was added for testing.

## Current limits

The quick/full JVM suites use existing fake providers and isolated synthetic
fixtures. They are not evidence of live Gemini quality, audio transcription,
TalkBack acceptance, or whole-app backup completeness. Existing Room-only
portable backup has a documented Inbox/task/voice-memory coverage gap; this
runner does not change backup production code or call that gap a pass.

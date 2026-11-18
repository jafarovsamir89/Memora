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
  never become identity links.
- `full` runs the complete unit suite, debug and AndroidTest builds, test Kotlin
  compile, lint, and the existing 31-state component visual comparison. Gradle
  receives `ANDROID_SERIAL` for the already-verified emulator.
- `device-smoke` assembles debug, installs with `adb -s SERIAL install -r`,
  opens `MainActivity`, saves one real emulator screenshot, and checks package
  version and `firstInstallTime`. It records ActivityManager startup time and
  waits 15 seconds after launch so the frame is not the brief Room/profile
  startup placeholder. It never runs `pm clear` or uninstalls.

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

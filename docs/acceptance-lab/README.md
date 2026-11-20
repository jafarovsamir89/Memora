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
python3 tools/run_memora_acceptance.py startup-probe --serial emulator-5556 --synthetic-user-id 11 --startup-samples 20
python3 tools/run_memora_acceptance.py finance-ui-journey --serial emulator-5556 --synthetic-user-id 10 --min-host-free-gib 1.0
python3 tools/run_memora_acceptance.py process-death-probe --serial emulator-5556 --synthetic-user-id 11
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
settings. Before saving the landscape screenshot it waits for three stable UI
hierarchy samples containing `Katibə` and `Bu gün`, then allows two seconds for
the frame to settle. This guards against capturing a transient black surface
during Compose recreation; it is still necessary to review screenshots
manually. Reports and real screenshots stay under ignored `app/build` output.
Review screenshots manually for onboarding/UI state; the runner does not
claim visual acceptance from screenshot capture alone. Keep the seeded user
isolated; do not clear or uninstall the owner's app profile.

The bounded `volume-probe` mode is restricted to the separately named
`MemoraCleanInstallLab` Android user (the confirmed ID on this emulator is 11).
It adds deterministic fixtures for 100 People and 1,000 rows/events in Finance,
Cases, Notes, an editable Table, and the existing encrypted local task store to
that synthetic profile, refusing unexpected records or a fixture that would
need to shrink. It verifies each row and Case / Note / Person revision before
and after insertion, and verifies stable IDs plus deterministic per-store
digests on repeat runs. For People it measures the scoped 100-record list, a
30-row page, and an in-memory exact filter. It records device-local
Room/repository and encrypted file-store p50/p95 timings plus instantaneous
process-memory snapshots. Fast paths report microseconds so sub-millisecond
samples are not rounded to zero. These are warm local reads; they do not
represent UI-frame latency, cold-cache behavior, peak memory or ANR risk. The
initial campaign uses both sizes; a later 1,000-row retest can use
`--volume-count 1000` because fixtures are monotonic and stay in that isolated
synthetic profile.

`startup-probe` measures repeatable process-cold launches of `MainActivity` for
the exact named synthetic users only (`MemoraReliabilityLab` or
`MemoraCleanInstallLab`). The selected user must already be current and have
Memora installed. Each sample force-stops that package for that user, runs
`am start -W --user <id>`, records ActivityManager `TotalTime`/`WaitTime` and
host ADB round-trip time, then allows two seconds for the local screen/profile
read to settle. It reports nearest-rank p50/p95 over 5–50 samples (default 20),
captures the final screen, and verifies the user and `firstInstallTime` stayed
unchanged. The app or OS data/cache is never cleared. This measures process-cold
launch with a warm emulator/OS cache; it is not a cold boot, a guarantee that all
UI data finished loading, or a physical-device benchmark. The runner does not
invoke the assistant or an AI/model API. The report is written under the run ID
folder described above.

`finance-ui-journey` requires the already seeded `MemoraReliabilityLab` user 10
to be the current emulator user, with both app and AndroidTest packages present.
It verifies exact lineage sentinels, then idempotently adds and reads synthetic
data through existing protected repositories: a 300 AZN BMW obligation, 100 AZN
advance allocated against it, 200 AZN remaining, a person, case, one task, four
case evidence links and an exact source turn. It cold-starts MainActivity and
drives the real Compose UI by visible accessibility text through Secretary →
Finance → Case → Person → Finance → Task → exact source → exact conversation,
then checks Android Back restoration. Exact IDs, displayed amounts/person/source
and unambiguous UI labels are required; ambiguous labels fail closed. Screenshots
and UI hierarchy captures are saved in the run's ignored output directory. The
journey does not send or retry a command, call an AI API, or clean up/delete data.

ACT-0278 resumed after host disk space was freed. A no-wipe cold launch of the
same AVD returned under serial `emulator-5554`; named synthetic users 10/11
were present. The initial post-boot user was 0, but no user-0 app records were
read. Subsequent checks explicitly selected the synthetic user.

The current 31-state component run on v178 is 30/31: only `confirmation-light`
fails at 9.020% changed pixels / 9.942 mean RGB delta. Two targeted captures
reproduce it. The real ignored screenshot
`apps/android/app/build/ui-visual-regression/confirmation-light.png` shows the
sheet starting at y=1574 instead of baseline y=1637 (63 px / 24 dp at 420 dpi).
All 30 other states pass. No baseline or threshold changed; cause remains
unresolved and no golden replacement is proposed as accepted.

The earlier slow user-11 repository measurements did not reproduce after the
cold AVD relaunch. On the same persisted fixture, v178→v179 measured case search
269/339 ms and oldest-conversation search 1,133/1,398 ms p50/p95; v179→v180
measured 263/345 ms and 1,089/1,238 ms. Baseline v138 was 258/322 ms and
1,094/1,284 ms. All five fixture digests matched, updates inserted zero rows,
and `firstInstallTime` stayed unchanged. Reports are `20261119T160641.929441Z`
and `20261119T160907.809428Z`. The prior v175/v176 slowdown appears transient,
but the exact contention cause is not proven. Finance analytics p95 is variable;
no broad performance PASS is claimed. These are warm local repository reads, not
UI/network/model latency or peak-memory results.

The full Gradle block after ACT-0278 passed: 84 suites / 431 JVM tests, 0
failures/errors, one opt-in live test skipped; debug and AndroidTest APK builds,
AndroidTest Kotlin compilation, and lint 0/90/2. APK v181 (`acceptance-lab-v181`,
SHA-256 `b71e2321ab3a338d5f20a5f29c1deb870037cfd011c2289b2198724a23aef7d2`) was
built but not installed. The golden task was intentionally not part of this Gradle
block; its separate full visual comparison remains 30/31.

After that cold boot, ACT-0279 ran `device-lineage` on synthetic user 10 without
seeding or clearing data. APK v181 updated over v180 with `adb install --user 10
-r`; all six lineage sentinels remained at count 1 before and after installation,
launch, force-stop/restart, and Activity recreation/rotation. `firstInstallTime`
remained `2026-11-18 04:54:50`. The run is overall PASS; real screenshots were
reviewed under ignored `apps/android/app/build/acceptance-lab/20261119T162401.137073Z/`.
The portrait Today screen is clear. At the initial landscape scroll position, the
voice card is partly below the bottom-navigation boundary; investigate this as a
landscape UX issue. P0.5 remains partial because only six sentinels, not every
store/link/history/setting/consent/onboarding path, are covered together.

The same continued session ran `startup-probe --startup-samples 20` on synthetic
user 11 / v181: PASS, process-cold MainActivity ActivityManager TotalTime p50/p95
1,218/2,806 ms, WaitTime 1,263/2,829 ms, ADB round-trip 1,339/2,915 ms. The
first launch was 4,371 ms. OS/storage caches remained warm, and `am start -W`
does not establish first-frame or content-ready latency. Real final screenshot
was reviewed; no data was seeded or cleared. Report is ignored at
`apps/android/app/build/acceptance-lab/20261119T162922.803137Z/report.json`.

The 1,000-row profile was subsequently checked through v182→v183 and v183→v184
updates. v182→v183 preserved all five fixture digests with zero inserted rows.
A target=100 invocation against the persisted 1,000-row set initially returned
FAIL before any fixture write because the probe correctly refused to shrink it;
that report is `20261119T163144.557747Z`. The test-only probe/runner was hardened
to validate the larger fixture and emit `NOT APPLICABLE` for the smaller size,
then continue to the 1,000 target. The repeated combined run `20261119T163613.536481Z`
reported 100 `NOT APPLICABLE`, 1,000 `PASS`, five stable digests, zero inserts
and preserved package lineage. This does not count the lower-size skip as a pass.
The first combined report incorrectly showed overall PASS while including a
`NOT APPLICABLE` size. The runner now aggregates any such matrix skip as PARTIAL.
Corrected combined run `20261119T164435.213264Z` updated v184→v185 and reported
overall PARTIAL (100 NOT APPLICABLE; 1,000 PASS), with five stable digests,
zero inserted rows and unchanged package lineage. Subsequent verification updated synthetic user10 v185→v186 with six exact
sentinels and unchanged `firstInstallTime`, then user11 v186→v187 with all five
volume digests stable and zero inserts. The target100 skip remained NOT APPLICABLE
and target1000 PASS, overall PARTIAL. A read-only user10 sentinel verify after
the shared v187 update also passed. Both synthetic profiles are read back on v187;
user0 was never inspected. The reviewed landscape capture still shows the voice
card partly behind the bottom-navigation boundary at the initial scroll position.

Python tools 68/68 and the full Gradle block passed (84 suites / 431 JVM tests,
0 failures/errors, one opt-in live test skipped; debug/AndroidTest build and
Kotlin compile; lint 0/90/2). APK v185 was built but not installed; SHA-256
`99d4f1752a01496da42e736c6dd793ac8910adb21da6360d3672de75f0160bd5`.

ACT-0276 on synthetic Android 14/API34 user 10 updated v170→v171 and preserved
`firstInstallTime`. The persistent route passed: Secretary→BMW finance/case/person/
task/exact source/conversation and Android Back; ambiguous Ruslan clarification
with `NEEDS_CONFIRMATION` and no write; correction action→exact-ID unsent draft→
45,00 AZN Finance detail→append-only correction history→Back to draft. The
AndroidTest repository fixture created a separate synthetic 50→45 AZN receipt
correction through the existing correction method, then read back one -500-minor-
unit `amount_correction`, two source messages and one result targeting the same
finance ID. This is not a Gemini action and the UI draft was not sent. Final run
`20261119T135855.309066Z` PASS; its 20 real screenshots and XML are ignored under
the run directory. Python tests 46/46; full Gradle BUILD SUCCESSFUL (84 suites /
431 JVM tests, 0 failures/errors, 1 opt-in skip; `testDebugUnitTest` UP-TO-DATE),
debug/AndroidTest builds and AndroidTest Kotlin compile PASS, lint 0/90/2.
Vertical scroll now uses the screen center to avoid edge-Back interference; the
Back assertion checks exact finance rows because Compose omits the selected-tab
label from that accessibility snapshot. LAB-03 remains partial: same-intent
manual retry across a new turn and live Gemini semantics remain NOT TESTED.
Code commit `d60c1e0` contains the verified fixture/harness changes; the docs
handoff and push are recorded in the ACT-0276 follow-up.

ACT-0275 supersedes the old v148 device status: on Android 14/API34 synthetic
user 10, the v166→v167 update preserved `firstInstallTime`, and the persistent
UI route passed Secretary→BMW advance→Case→Person→Finance→Task→exact source→
exact conversation plus Android Back. The AndroidTest fixture read back the
300/100/200 AZN amounts and verified an ambiguous Ruslan action had no target and
left finance count unchanged (104→104). Fifteen real screenshots cover the
route; an additional capture shows the clarification and no-write state. The
v166 integrated attempt failed because one harness swipe moved the source-open
button off-screen; the bounded eight-swipe helper corrected this harness issue,
and v167 passed. The persistent fixture and screenshots are synthetic and the
run uses no provider/network. A confirmed action is preserved after a failed
final reply and a localized warning tells the user to inspect its result before
resending; this checks UI behavior, not cross-turn semantic de-duplication.
Correction UI/readback and a deliberate same-intent resend across fresh turns
remain open; LAB-03 is partial. Full JVM tests are 84 suites / 431 tests, 0
failures/errors, 1 opt-in skip; lint 0/85/1; Python harness 49/49.
Implementation/test commit `4d384ad65f5ced7bf52405159ad3a99a7c36e82d` is
pushed to `origin/ui/redesign-v1`; local/tracking/remote feature refs match,
and remote `main` remains `ac621919aca09ece6e292eae708d55cc09de16d7`.

ACT-0273 updated the detail check to scroll to the top before asserting its amount
and direction: navigating back to the same Finance detail can restore the prior
scroll offset, leaving header fields above the viewport. The v148 AndroidTest
fixture/readback passed and the v147→v148 update preserved `firstInstallTime`.
Subsequent UI-only v148 runs proved the header check and case/person/finance/task
routes, then exposed repeated source labels and a missing visible assistant reply
after opening the exact conversation. The runner now targets the clickable lower
source link. ACT-0274 changes Secretary's reverse-layout focus to the model row of
the exact turn; the focused Compose regression passes 1/1 and the Python runner
suite passes 42/42. The emulator still runs old v148, so this runtime fix is not
device-verified. Host free disk is 836 MiB, below the 1 GiB lab floor; no new APK
was built or installed. The complete BMW/person/task/source/conversation/Back UI
journey, persistent ambiguity/correction, and retry/no-duplicate UI behavior
remain open; do not mark LAB-03 complete.

### P1.3 recovery checks (partial)

ACT-0271 adds an emulator UI check on synthetic user 11: a clearly marked
unsent Secretary draft survives portrait→landscape Activity recreation and
return to portrait; the draft is then cleared and the original rotation settings
are restored. A focused fake-permission Compose test verifies that denied
`RECORD_AUDIO` never reaches the recorder or STT gateway. The 54-test synthetic
fault/recovery set also covers provider 503/offline/timeout, post-write partial
results, same-turn duplicate suppression, cancellation, and simulated journal
reconciliation. These do not prove real network faults, OS process-death recovery,
physical-device behavior, microphone permission-dialog acceptance, live audio,
or TalkBack. Do not treat the partial set as LAB-09 completion.

ACT-0268 also changed the People/Projects/Notes `SavedObjectsScreen` from eager
card composition to a stable-ID-keyed `LazyColumn`. A Compose test with 100
synthetic people proves the last record is not composed initially and is
reachable by search. On the emulator, the exact 0099 result opened the matching
person detail and Android Back restored the same filtered list. At AZ
`fontScale=1.3`, visible cards and bottom navigation remained readable; the
emulator setting was restored to 1.0. Three brief `dumpsys gfxinfo` screen-entry
samples had only four frames each: p50 97–109 ms, p95 101–113 ms, and 2–4 janky
frames per trial. The earlier eager-list samples were p50 350–600 ms / p95
400–800 ms, but the tiny frame sample is insufficient for a performance PASS
or a latency guarantee. Cold/startup timing, scroll-to-end, long conversations,
peak memory and ANR remain open. Case search p95 at 1,000 rows also needs UI
tracing; its repository latency alone does not prove main-thread jank.



ACT-0269 extends the isolated user-11 AndroidTest probe with 1,000 synthetic
conversation turns. It verifies exact row readback and deterministic digest,
rejects unexpected scope rows, walks the archive in 34 exact pages, and searches
for the unique oldest turn. A repeated `volume-probe --volume-count 1000` run
inserted zero turns and preserved package `firstInstallTime` across v137→v138.
The latest 20 warm repository search samples measured p50 1,094 ms / p95
1,284 ms; they exclude UI, network and model time. The Memory Compose test opens
the oldest search result and full assistant reply, and the emulator check confirms
Back restores the query. The list preview is capped to four lines while details
retain the complete turn.

The visual capture runner waits until UIAutomator exposes the Compose gallery
heading (or the confirmation modal title) before taking a screenshot. This avoids
comparing a partially drawn cold-start frame with a golden. It does not change
thresholds or goldens. The standalone 31-state result is 30/31;
`confirmation-light` remains a known mismatch. The full Gradle run was not repeated
after this gate change, so keep overall acceptance as FAIL.

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


## ACT-0280 — Landscape Today and v190 persistence/volume recheck

On Android 14/API34 emulator `emulator-5554`, Today landscape was compacted so
its heading/date and conversation card share the initial viewport. Synthetic
user 10 v188→v189 lineage passed with six sentinels and markerless profile
preserved through update/restart/rotation; `firstInstallTime` stayed
`2026-11-18 04:54:50`. User 10's read-only check after shared v190 also passed.
True portrait, landscape and AZ `fontScale=1.3` screenshots are in ignored run
`20261119T171452.892518Z/`; the larger-font landscape screenshot confirms the
whole voice target and priority heading sit above bottom navigation. Synthetic
user 11 v189→v190 retained five 1,000-row fixture digests with no inserts.
Requested size 100 is `NOT APPLICABLE` against its existing 1,000-row fixture,
so the combined volume matrix is `PARTIAL`, while size 1,000 itself passes.
Warm repository measurements are recorded in the report and are not UI latency.
The component gallery remains 30/31 because `confirmation-light` still differs;
no golden or comparison threshold was changed. Code/test commit
`804eb46be66563058f5538268e3ee9e45300bb41` contains the landscape fix. No real
profile, physical phone, AI/network, microphone or paid API was used.


## ACT-0281 — Synthetic process-death and key-rotation recovery

`process-death-probe` builds and installs matching debug/AndroidTest APKs over
one explicitly named synthetic Android user. Instrumentation in that target
user checks that its two named disposable DB/envelope fixtures and aliases do
not already exist. It prepares either an open transaction or completed SQLCipher
rekey, emits the prepare PID, then the runner force-stops only the selected
user's app/test packages. The runner requires that PID to disappear and
instrumentation recovery to run under a different PID. It verifies committed
transaction durability/uncommitted rollback or staged-key promotion, checks the
named artifacts/aliases were removed, and launches MainActivity. It does not
read or mutate the user's actual app database. Reports remain ignored in build
output. The 2026-11-19 Android 14 run passed both cases for synthetic user 11;
see ACT-0281 in the diary/action log. These test-only probes do not validate
real network faults, Secretary UI draft survival after OS process death, or
physical-device behavior.

## ACT-0282 — Secretary unsent draft after OS process death

A real Android 14/API34 emulator check complements the Compose
`StateRestorationTester` regression. Use only the named synthetic user 11
`MemoraCleanInstallLab`, never owner user 0. With the app v194 open to Secretary,
enter one unique synthetic string in the composer and do not tap Send or grant
text consent. Move to the Android Home activity, wait for the app to stop, and
run `am kill --user 11 ai.memora.app`. Confirm the old app PID disappears, then
launch `ai.memora.app/.MainActivity` for user 11 and verify that the unsent text
returns to the composer. Clear the draft without sending it. This manual
sequence does not prove restoration if Android kills the process before saving
Activity state.

On 2026-11-19 the old PID 15671 was replaced by 15943 and the unique draft was
visible after relaunch. The Compose regression additionally verifies that
restoring a draft neither calls its fake text-turn provider nor appends a
conversation message. A subsequent read-only v194→v195 1,000-turn probe retained
all five fixture digests, inserted 0 rows, and preserved `firstInstallTime`.
Case search p50/p95 was 253.021/325.412 ms, oldest conversation search
1,097/1,168 ms, and finance analytics 155/216 ms; these are warm local
repository measurements, not UI/frame timings. Screenshots and report are
ignored under `apps/android/app/build/acceptance-lab/ACT-0282-secretary-draft-process-death/`.
Physical-device behavior, low-memory pre-save process death, TalkBack and real
provider/network faults are still not tested.

## ACT-0283 — Traverse the full synthetic conversation archive

On the explicitly pinned Android 14/API34 emulator, use only synthetic user 11
`MemoraCleanInstallLab` with the 1,000-turn fixture. Open Memory → conversation
history, confirm newest turn `0999`, and use the real UI's “load more” control
until the oldest turn `0000` is visible. This exercises pagination, lazy
rendering and the long archive in the app; it does not mutate the fixture.

The 2026-11-19 run on v195 reached `0000` in 34 pages. Starting/newest and
oldest screenshots were captured from the emulator and visually reviewed. The
oldest card retains its date and exact-open action while the long preview is
ellipsized. ADB action-to-first-row p50/p95 was 4.656/4.746 seconds, but this
includes input injection, accessibility hierarchy dumps and host polling; do
not interpret it as a UI response latency.

`gfxinfo` over the complete ADB-driven traversal reported 98.56% janky frames
(p50/p90/p95/p99 46/89/97/113 ms). Six-swipe samples on the same emulator were
also slow on Memory (93.94%), Today (98.95%) and More (100%). Because the
reference screens show similar frame statistics, this method/environment does
not isolate a Memory-specific regression or establish physical-device
smoothness. Status: archive traversal PASS; application smoothness on a target
device and Macrobenchmark/Perfetto profiling NOT TESTED. Do not use these
emulator values as a release threshold. Screenshots and the detailed report
remain ignored under
`apps/android/app/build/acceptance-lab/ACT-0283-memory-ui-scroll/`.

## ACT-0285 — Bounded diagnosis of the confirmation-sheet golden mismatch

On Android 14/API34 `emulator-5554`, synthetic Android user 11, the original
`confirmation-light` capture still fails against the reviewed golden: 9.020%
changed pixels / mean RGB delta 9.942. The sheet begins at y=1574 versus y=1637
in the golden, a 63 px / 24 dp vertical offset at 420 dpi.

The capture produced after the old 0.85-second delay was byte-identical to the
current settled original-source capture, so settle timing is not the cause. A
temporary debug-only attempt to hide system bars and use edge-to-edge for the
modal did not make the comparison pass (9.013% / 9.843) and was reverted.
Removing manual `navigationBarsPadding()` had also been ineffective in an
earlier bounded test. The actual root cause remains unproven. Preserve the
golden and thresholds; do not update them. The original-source debug build v198
was installed with `adb install --user 11 -r`; `firstInstallTime` remained
`2026-11-15 16:15:34`. No runtime or profile data was changed.

The screenshots and compact JSON report are ignored at
`apps/android/app/build/acceptance-lab/ACT-0285-confirmation-golden-diagnostic/`.
Status: this golden remains FAIL and LAB-07 remains partial. Continue other
independent visual/accessibility checks; revisit only with a new diagnostic
approach.

## ACT-0284 — Memory at AZ fontScale 1.3, both themes

On synthetic Android user 11, set the app locale to Azərbaycan, switch the
app's own appearance setting between light and dark, and set only that
synthetic user's system font scale to 1.3. Review the Memory header, search,
filter chips, archive cards, detail dialog and bottom navigation. Check that
filters may wrap instead of clipping and that long conversation details scroll
inside their dialog while its actions remain visible. Use UIAutomator bounds to
check at least 48dp for the search/filter/new-memory and bottom-navigation
controls. Restore the original locale/theme/font scale after the run.

On 2026-11-20, the AZ Memory screen was visually checked in both app themes.
Filter chips wrapped to a second row without clipping. The preview remained
clamped and scrollable; opening a synthetic row showed the exact turn, and the
long dialog scrolled to the assistant response. Back preserved the archive
filter/position. Measured targets at density 420 were search 371×73dp,
conversation filter 166×48dp, new-memory action 371×48dp, bottom navigation
76×80dp, dialog Close 71×48dp and Delete 110×48dp. Palette contrast tests
passed 4/4. These results apply to the Memory screen and palette pairs only;
they do not replace the full-screen accessibility audit or TalkBack check.
App theme, AZ locale, font scale and system night mode were restored. Real
screenshots and compact report stay ignored under
`apps/android/app/build/acceptance-lab/ACT-0284-memory-accessibility/`.


## ACT-0286 — Secretary and Cases at AZ fontScale 1.3

On Android 14/API34 `emulator-5554`, synthetic Android user 11, inspected the
Secretary composer with the keyboard open and the Cases list/search/detail
flow. Secretary's composer remained above the IME; AZ labels wrapped, with
48dp Send and 52.2dp voice targets. In Cases, filters wrapped to two rows,
search for unique synthetic marker `0999` opened its detail, and Back restored
the query/filter. Search target measured 363.4×73.1dp; all five filters were
48dp high. Results below the open keyboard remain scrollable. An unsent
synthetic composer draft was cleared without sending.

System font scale was restored to 1.0; app theme is light, locale AZ, selected
tab Today. v198 and its `firstInstallTime` remained unchanged. Six real
screenshots and a compact report are ignored under
`apps/android/app/build/acceptance-lab/ACT-0286-secretary-large-text-keyboard/`.
TalkBack, app-wide contrast, physical-device acceptance and full Gradle are
NOT TESTED. This screen slice passes but does not close LAB-07: the
`confirmation-light` golden mismatch remains unresolved.

## ACT-0287 — Today and More at AZ fontScale 1.3

On Android 14/API34 `emulator-5554`, synthetic Android user 11, checked Today
and More at AZ `fontScale=1.3` in both light and dark appearance. Today’s
large title, date, conversation card and metric labels remained readable; its
52.2dp microphone control and priority cards were reachable. More’s long
section descriptions wrapped without visible clipping, and scrolling reached
Settings, backup, the financial report and its explicit scope caveats.
Measured bottom-navigation targets were 76.2×80dp; the visible More cards and
header security action exceeded or met 48dp. Finance data came from the
synthetic test profile; no operation was executed.

Nine genuine emulator screenshots and a compact report remain ignored under
`apps/android/app/build/acceptance-lab/ACT-0287-today-large-text/`. Restored
system font scale 1.0, app theme light, locale AZ and Today tab. No data writes,
AI/network/microphone, owner user 0 or production changes. TalkBack, app-wide
contrast, physical-device checks and full Gradle are NOT TESTED. This targeted
slice does not close LAB-07: `confirmation-light` remains unresolved.

## ACT-0288 — Settings at AZ fontScale 1.3

On Android 14/API34 `emulator-5554`, synthetic user 11, inspected Settings in
AZ at `fontScale=1.3`. Gemini descriptions and appearance choices wrapped and
remained readable; permission status and long exact-alarm guidance remained
reachable through scrolling. Gemini 3.5 Flash Lite remained selected. The
Firebase App Check section was reached, but its token field was not focused,
read or changed. A transient bottom screenshot was deleted due possible
sensitive input; only a nonsensitive top capture and restored-state capture
remain. Font scale returned to 1.0; light theme and AZ locale were preserved.
No provider, consent, permission, data or profile changes. TalkBack, app-wide
contrast, physical device and full Gradle are NOT TESTED.

## ACT-0289 — Bounded confirmation-sheet window/insets diagnosis

On Android 14/API34 `emulator-5554`, synthetic user 11, the debug gallery
Activity hides system bars, while the separate Material3 `ModalBottomSheet`
dialog becomes the active inset controller and reports a visible 48dp
navigation-bar frame. The emulator is in fully gestural mode. This is relevant
new evidence but does not establish why the reviewed screenshot is shifted.

A temporary debug-only variant set `contentWindowInsets` to zero and removed
the inner manual `navigationBarsPadding()`. Debug build v199 succeeded; the
targeted `confirmation-light` comparison remained exactly 9.020% changed pixels
and mean RGB delta 9.942. The experiment was reverted. The original-source
debug v200 was then assembled and installed with `adb install --user 11 -r`;
MainActivity was relaunched and the original comparison failed identically.
No golden/threshold/source change remains; the ignored screenshot is diagnostic
evidence only. Full Gradle is NOT TESTED. Preserve the baseline and continue a
different independent test; do not repeat this insets variant without new
evidence.


## ACT-0290 — More, Tasks, Calendar and Notes at AZ fontScale=1.3

Read-only UI slice on Android 14/API34 emulator, synthetic user 11, light theme.
Tasks showed 0 assistant tasks and 1,000 local synthetic rows; the name field
remained usable with IME open and no task was submitted. Calendar showed the
correct zero-event empty state. Notes showed 30/1,000, exact synthetic search
`0999`, then Load more advanced to 60/1,000. Large AZ text wrapped and checked
controls remained reachable. Exact note detail navigation is BLOCKED for this
volume fixture because its marker IDs are not UUIDs accepted by `AppRoute.target`;
no production-route defect is inferred. Nine real screenshots and JSON report
are under the ignored `apps/android/app/build/acceptance-lab/ACT-0290-more-screens-large-text/`.
Font scale returned to 1.0 and Today was restored. Dark theme, TalkBack and full
Gradle are NOT TESTED. No records or production code changed.


## ACT-0294 — Inbox and Portable Backup at AZ fontScale=1.3

On Android 14/API34 emulator `emulator-5554`, inspected Inbox and Portable
Backup using synthetic Android user 11, AZ locale, light theme and
`fontScale=1.3`. The Inbox list, search and filters remained readable in the
reviewed viewport. The backup page showed its title, encryption guidance,
warning, password fields and actions. With the keyboard open, lower actions can
move below the viewport; dismissing the keyboard and scrolling exposes both
actions. No password was entered and create/import/file-picker actions were
not invoked.

Added an AZ/RU/EN/default-locale notice that portable backup contains only the
protected database and currently excludes Inbox captures, device tasks and
Voice Memory. This is a disclosure of the existing gap, not a backup/restore
implementation change. `UiShellTest.portableBackupExplainsWhichLocalStoresAreNotIncluded`
passes. Full JVM suite: 84 suites / 434 tests, 0 failures/errors, 1 opt-in skip.
Debug/AndroidTest builds, AndroidTest Kotlin compile and lint passed (0 errors,
90 warnings, 2 hints). Full component visual comparison was 30/31: only the
pre-existing `confirmation-light` mismatch remains (9.020% changed pixels,
mean RGB delta 9.942); no golden or threshold was changed. An initial full-run
failure in the secretary restoration test passed in isolation and in the
subsequent full suite.

The component-regression task installed APK v203 `acceptance-backup-scope-note`
as an update. Synthetic user 11 launched to Today and continued to show its
existing 1,000-case fixture; no data was cleared. System font scale was restored
to 1.0 and Today was left selected. Real screenshots and compact report remain
ignored under
`apps/android/app/build/acceptance-lab/ACT-0294-inbox-backup-large-text/`.
Whole-app backup, TalkBack, dark-theme sweep, physical Redmi and owner
acceptance remain open; LAB-06 and LAB-07 are partial.

## ACT-0295 — Inbox and Portable Backup in dark theme at AZ fontScale=1.3

On the same Android 14/API34 emulator, synthetic user 11, app v203, used the
Settings appearance control to select dark mode and inspected Inbox and
Portable Backup in Azerbaijani at `fontScale=1.3`. The Inbox heading, subtitle,
new-note action, search, filters, empty state and import/export labels remained
readable in the captured viewport. The backup title, encrypted-backup guidance,
scope warning, password guidance and both actions also remained readable.
After scrolling, UIAutomator reported the backup create/file clickable parents
as `[116,1653][964,1779]` and `[116,1811][964,1937]`; each is 126 px / 48 dp at
420 dpi. No password or action was used.

Restored the app appearance to light, system font scale to 1.0, and Today tab.
Real emulator screenshots and a compact report are ignored under
`apps/android/app/build/acceptance-lab/ACT-0295-inbox-backup-dark-large-text/`.
No code change or automated test was made. This is not a computed contrast,
TalkBack, keyboard-open dark-theme, physical-device or whole-app accessibility
pass; LAB-07 remains partial.

## ACT-0296 — Portable Backup fields with dark AZ keyboard open

On Android 14/API34 `emulator-5554`, synthetic user 11, v203, dark theme and
AZ `fontScale=1.3`, focused each empty backup password field without typing.
The IME opened while the scope warning and first field remained visible; the
second field could be focused from its visible upper portion. The keyboard
covers lower guidance/actions until dismissed. No action or text submission
occurred. Restored light appearance, fontScale 1.0 and Today.

Real screenshots and a compact report are ignored under
`apps/android/app/build/acceptance-lab/ACT-0296-backup-ime-dark-large-text/`.
This was a manual visual/focus check only: TalkBack, form submission, keyboard
button behavior, physical Redmi and owner acceptance are NOT TESTED. LAB-07
remains partial.

## ACT-0300 — Projects subtitle regression guard

Verified the current route map: People uses `SavedObjectsScreen`, while
Projects and Notes use `ProjectNotesScreen`. That screen already selects
`projects_hint` for Projects and `notes_hint` for Notes, so the suspected
cross-route subtitle defect was not present. Added
`UiShellTest.projectsRouteUsesProjectContextRatherThanPeopleSubtitle` to guard
the distinct Project title/subtitle and reject People copy on that screen.

- PASS: focused Project-route Compose regression.
- PASS: full JVM 84 suites / 435 tests / 0 failures / 0 errors / 1 opt-in live
  test skipped; debug/AndroidTest builds, AndroidTest Kotlin compile, lint
  (0 errors / 90 warnings / 2 hints), and `git diff --check`.
- PASS: APK v205 update on synthetic user11 preserved `firstInstallTime`
  `2026-11-18 05:13:46`.
- PASS: AZ/dark `fontScale=1.3` view showed the Projects title, correct subtitle,
  search/refresh controls and empty state without clipping. No data was changed.
- Restored light/AZ/fontScale 1.0/Today. No runtime source change.
- NOT TESTED: TalkBack, computed contrast, physical Redmi and owner acceptance.

Real screenshots and JSON report are ignored under
`apps/android/app/build/acceptance-lab/ACT-0300-projects-subtitle-dark-large-text/`.

## ACT-0299 — Cases search and keyboard in dark AZ large text

On Android 14/API34 emulator `emulator-5554`, synthetic user11, v204, opened
Cases in AZ/dark at `fontScale=1.3`, focused search, and entered only synthetic
query `0999`.

- PASS: search, result-count guidance and all five status filters remained
  readable and visible with the Android keyboard open.
- PASS: UIAutomator measured filter clickable parents at 126 px / 48 dp at
  420 dpi; search target remained 954 x 192 px.
- OBSERVED: the keyboard reduces the list viewport and partially covers the
  first case card. Full card content was not evaluated until the keyboard was
  dismissed; no swipe/scroll with IME open was performed.
- No case action or data write. Query cleared. Restored light/AZ/fontScale 1.0/
  Today. No source change or Gradle run.
- NOT TESTED: TalkBack, computed contrast, physical Redmi, live AI/network/audio
  and owner acceptance. LAB-07 remains partial.

Real screenshots and JSON report are ignored under
`apps/android/app/build/acceptance-lab/ACT-0299-cases-ime-dark-large-text/`.

## ACT-0298 — People subtitle and related dark-theme large-text review

On Android 14/API34 emulator `emulator-5554`, named synthetic user 11, APK v204, checked People, Cases and Finance in AZ/dark at `fontScale=1.3`. The People route had reused the localized saved-notes subtitle. It now reuses the existing `people_hint` resources; an integrated Compose regression requires that People text and rejects the old notes text. The existing finance exact-ID test uses UUIDs; this volume fixture's marker IDs are intentionally not UUIDs, so finance-item routing was not established by this visual check.

- PASS: focused People subtitle regression.
- PASS: full JVM suite, 84 suites / 434 tests / 0 failures / 0 errors / 1 opt-in live test skipped.
- PASS: debug/AndroidTest APK builds, AndroidTest Kotlin compile, lint (0 errors / 90 warnings / 2 hints), and `git diff --check`.
- PASS: v204 `adb install --user 11 -r` update; synthetic user11 `firstInstallTime` remained `2026-11-18 05:13:46`.
- PASS: corrected People subtitle/cards, selected synthetic person detail, Cases list and selected case detail, and Finance AZ filters/expense cards remained readable in the inspected dark large-text views. No write/action was invoked.
- Restored AZ/light/fontScale 1.0/Today. No user data, API, network, microphone, consent, permission or provider change.
- NOT RUN: component golden suite for this package; latest known result remains 30/31 with `confirmation-light` failing at 9.020% / 9.942 mean RGB delta. TalkBack, calculated contrast, physical Redmi and owner acceptance remain NOT TESTED.

Real screenshots and JSON report are ignored under
`apps/android/app/build/acceptance-lab/ACT-0298-related-lists-dark-large-text/`.
LAB-07 and LAB-06 remain partial; this is not production readiness.

## ACT-0297 — Secretary dark theme and keyboard at AZ fontScale=1.3

On Android 14/API34 emulator `emulator-5554`, synthetic user 11, v203, the
Secretary screen was reviewed in Azerbaijani/dark at `fontScale=1.3` using only
synthetic archived conversation content. With the empty composer focused, the
IME opened and the microphone, input and disabled empty Send stayed above it.
UIAutomator measured the clickable microphone as 52.2dp square and Send as
48dp square. No text was entered/sent, microphone activated or consent changed.
Restored light appearance, fontScale 1.0 and Today.

Screenshots/report remain ignored under
`apps/android/app/build/acceptance-lab/ACT-0297-secretary-dark-large-text/`.
This is not a TalkBack, contrast, voice-provider, physical-device or owner
acceptance pass; LAB-07 remains partial.

## ACT-0291 — People title duplication and Finance large-text slice

At Android 14/API34, synthetic user 11, AZ `fontScale=1.3`, the v200 People
screen showed the route title in both the top bar and page `ScreenHeading`.
The duplicate page title was removed; the descriptive subtitle remains. An
integrated single-title Compose regression and two person/card tests pass.
Debug v201 assembled and was installed with `adb install --user 11 -r`;
`firstInstallTime` remained unchanged. Finance list and empty debt-filter state
were visually reviewed using synthetic records. Five real screenshots and a
JSON report are under the ignored
`apps/android/app/build/acceptance-lab/ACT-0291-people-title-finance-large-text/`.
Font scale returned to 1.0 and Today was restored. Full Gradle, dark theme and
TalkBack are NOT TESTED.
## ACT-0292 — Tables at AZ fontScale=1.3

On Android 14/API34 emulator `emulator-5554`, synthetic Android user 11, app v201, light theme, inspected the Tables directory and the synthetic 1,000-row / 1-column table at Azerbaijani `fontScale=1.3`.

- PASS: list title, explanatory text, create/search controls, fixture table name and count remained readable.
- PASS: detail title/count, export/add/schema/history controls and visible row cards remained readable. UI hierarchy reported row edit/delete action bounds of 126 px at 420 dpi, i.e. 48 dp.
- PARTIAL: manual scroll reached row 0088; the last of 1,000 rows was not reached. This confirms scrolling of the sampled region only.
- PASS: no row was edited or deleted; import/export was not invoked and no profile data changed.
- Code observation: `TablesScreen` calls `repository.tables(scopeId)`; `ProtectedDomainRepository.tables()` attaches `dao.rows(table.tableId, scope)` for every active table before Compose lazy composition. This means UI virtualization does not bound that repository query/materialization. It is a code-path finding, not measured latency or memory regression.
- NOT TESTED: full 1,000-row traversal, search/import/export behavior, cold/warm latency, peak memory, frame time, ANR, dark theme, TalkBack, physical Redmi and full Gradle.

Five real emulator screenshots and a compact JSON report are stored under ignored `apps/android/app/build/acceptance-lab/ACT-0292-tables-large-text/`. System font scale was restored to 1.0 and Today tab. LAB-07 and LAB-08 remain partial; no UI, data, fixture, golden or threshold change is implied.

## ACT-0293 — 1,000-row table repository timing

Existing `volume-probe --volume-count 1000` ran on Android 14/API34 emulator `emulator-5554`, named synthetic user 11. The build installed v202 over v201; `firstInstallTime` remained unchanged. Instrumentation PASS: 1,000 rows remained in Finance, Cases, Notes, editable Table, Tasks and conversations, plus 100 People. Every store reported zero rows inserted and all fixture digests matched prior successful run `20261119T175400.046748Z`.

The complete table snapshot repository path was sampled 20 times after one warm-up: p50 2,615 µs / p95 2,995 µs. This warm local repository measurement does not measure Compose rendering, scrolling, cold-cache behavior, frame timing or ANR. One process-memory snapshot at probe end was PSS 189,385 KiB and Java heap used 17,990,232 bytes; it is not peak memory and cannot be attributed to the table call. No network/model call occurred.

The raw runner report is ignored at `apps/android/app/build/acceptance-lab/20261120T103624.663755Z/report.json`; app update, fontScale 1.0 and Today state were verified. Full JVM suite, lint, visual golden suite, UI performance, dark theme, TalkBack, physical Redmi and owner acceptance remain NOT TESTED. LAB-08 remains partial.

## ACT-0291 — People title duplication and Finance large-text slice

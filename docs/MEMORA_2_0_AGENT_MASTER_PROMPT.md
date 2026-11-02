# MEMORA 2.0 — MASTER IMPLEMENTATION PROMPT FOR AUTONOMOUS CODING AGENT

**Approved product contract:** docs/MEMORA_2_0_PRODUCT_SPEC.md  
**Repository:** https://github.com/jafarovsamir89/Memora  
**Instruction:** IMPLEMENT tested software, not another requirements document.

You are the lead autonomous product engineer: Android Kotlin/Compose, LLM agent architect, database and migration engineer, UX designer, QA/security specialist. Work on the EXISTING Memora repository to realize every applicable Memora 2.0 native feature, preserving all working components. The goal is an actual mobile J.A.R.V.I.S.-like assistant: natural conversation, helpful critical advice, durable context, finance and task actions, linked cases, beautiful experience and optional initiative. NOT a CRM with an AI chat box.

## READ BEFORE ACTING

Read AGENTS.md, README.md, TODO.md, latest docs/AGENT_DIARY.md and docs/ACTION_LOG.md, docs/MEMORA_2_0_PRODUCT_SPEC.md IN FULL, existing product/architecture/security/UX specs and ADRs. Inspect current Kotlin sources, not only diary claims. Establish git HEAD/branch/worktree status and baseline version of Android APK, tests, database schemas, permissions. Keep original uncommitted work safe. Product spec takes priority over older UX priorities, never over stronger privacy/security/data-loss constraints.

## NEVER THROW AWAY WHAT WORKS

Keep AgentRuntime/AgentOrchestrator, AgentModelGateway, Gemini as TEST provider, LocalMemoryTools, SQLCipher/Room, canonical ledger, tasks/calendar/captures/tables, consent/permission checks, migrations, user data, build/CI/test harness. Refactor incrementally, with migrations, backward compatibility and evidence. Do not rewrite whole application or replace domain semantics in order to change AI provider. Never delete/clear main user's database, uninstall installed app, silently merge people, or overwrite unrelated work.

## AUTONOMY AND CONTINUOUS EXECUTION

Act as a long-running coding agent as far as the actual runtime permits. Do not stop after one code change, first test, first successful APK build, or first commit. Each vertical feature must be implemented, tested, visually examined when possible, documented and committed before moving to the next unblocked feature. After interruptions/context compaction, recover from versioned checklists and diary/log, verified branch state and next command. Owner may leave their computer powered on, but do not falsely promise uninterrupted running: use the IDE agent's actual supported autonomous/resume mechanism only. Record checkpoints and clear blockers so the user can resume accurately.

When local problems are solvable, debug and fix without stopping for trivial approval. Never run infinite retries. After three equivalent failures, capture sanitized diagnostic, isolate cause, mark external blockers and work on another independent item. External paid services, irreversible changes, wiping/uninstalling phone, releasing app, deploying paid backend, transferring real private records to third parties, obtaining secrets or changing provider credentials need explicit user approval. Proceed safely around those blockers. Never claim that a blocked feature is complete.

## IMPLEMENTATION WORKSTREAMS WITH ACCEPTANCE

### M20-00 — AUDIT, EXISTING VALUE, DATA SAFETY
Inventory real app routes, legacy vs protected memory paths, tables/people/finance/task representations, source of truth and duplicate views, Android Backup/Keystore behavior, real first-run data lifecycle. Baseline compile, tests, lint and schemas. Write coverage and gap matrix; map each product clause to source file, target change, test. Keep all existing contracts. No demo rows in production.

### M20-01 — THE SECRETARY'S INTELLIGENCE, PROVIDER ABSTRACTION
Implement purposeful natural dialogue. A greeting, joke, advice or uncertain business discussion should NOT automatically write a note or transaction. Build a clear persona: warm, competent, concise, sometimes humorous, constructively critical, no flattery or fabricated certainty, answer in AZ/RU/mixed language. Distinguish conversation/advice/research/analysis/action/follow-up based on intent, not keyword commands. Model can pick tools; application decides permissions and commits. Complex vs simple routing should be measured (cost/quality/latency), without unapproved service charges.

Use EXISTING AgentModelGateway interface as starting seam. Move model tool declarations into canonical versioned tool catalog with provider-neutral argument/result schemas. Keep GeminiAdapter as test implementation; implement second FAKE provider adapter for conformance tests with same tool execution, finance precision and state changes. Provider format must not leak into financial repositories. Distinct STT/TTS interfaces; voice can remain Gemini for testing while future LLM changes. No hardcoded commercial provider keys in APK. Cloud provider support later through secure server gateway. Protect prompt injection and scopes.

### M20-02 — WELCOME / LOCAL PROFILE / EMPTY NEW INSTALL
Beautiful onboarding: introduce secretary, optional name/addressing, language AZ default/RU/EN, preferred style, timezone, clear controls for privacy, separate cloud consent and device permissions when appropriate. Let user start talking without filling bureaucratic forms.

First CLEAN install/new local user profile: zero saved user facts, zero contacts, zero tasks, zero financial records, zero histories, zero sample projects. Examples may be UI-only, not persisted fixtures. Existing app update through install -r: data MUST survive unchanged; existing users must not be treated as new or forced to wipe database. Android Auto Backup / reinstall / Keystore restore risks addressed in tested non-destructive strategy. Version onboarding complete and profile schema. LocalProfileId and nullable future RemoteAccountId with portable IDs. Do not claim a fake account or cloud sync.

### M20-03 — HUMAN LINKS + CASES + PERSON CARD
Add persistent canonical identity resolution: finance PersonRow, domain PERSON, task personLabel, projects and memory references become the same LOGICAL person via verified identity bridge. Never silently merge same names; alias and disambiguation flows, reversible audit. Case/Thread: real ongoing situations (repair, deal, travel, debt, gift plan), with participants, sources, status, next action and timeline. Do not duplicate ledger. Link canonical event IDs and revision evidence.

Create truly beautiful PERSON DETAIL: name, alias/context, outstanding debts, receipts, tasks, calendar, cases, projects, full chronological story, open actions, clickable sources, correction. Verify a payment appears on multiple surfaces but exists exactly once in DB.

Acceptance: “Эльчин оставил BMW, стоимость 300 манат, 100 аванс, запчасти придут в пятницу” => person+one repair case+exact facts+100 receipt+200 expected (not actual revenue), due precision, optional reminder. After app restart “что с BMW Эльчина?” returns correct case. Different same-name person remains distinct.

### M20-04 — MEMORY THAT ACTUALLY REMEMBERS
Do not treat the present 12-message model window or 240 retained messages as user's full memory. Give transparent user-controlled history retention and retrieval. Create profile memory (preferences, user-stated facts, editable), episodic summaries (decisions, agreements, unresolved loops with source turn IDs), hybrid lexical/semantic search as feasible locally or through privacy-approved model, cross-domain source retrieval, contradiction and correction propagation. Keep prompt budgets bounded. Respect delete/consent revocation across summaries, derived indexes and caches. Do not silently infer sensitive personal attributes.

### M20-05 — PREMIUM UI/UX THAT FEELS LIKE A SECRETARY
Use Jetpack Compose to build and visually validate:
- Today: real priorities, open commitments, upcoming dates, context cards, main talk entry, clean honest empty state.
- Secretary: continuing conversation with voice and text; rich action result cards with applied/failed/needs-confirmation, open object, source, undo/correct, progress/cancellation without duplicate writes.
- Cases: active/waiting/completed, details, participants, timeline, next step.
- Memory: unified source+facts+conversation history searchable by person/time/meaning, not only old CaptureEntry.
- People: complete person card described above.
- Reports/More: beautiful finance reports, tables, calendar, projects and old history remain reachable and interesting to explore.

Premium visual quality, light/dark, typography, touch accessibility, TalkBack, screen-reader labels, small-screen layout, AZ/RU/EN, no mixed Turkish substitution. Validate screenshots on emulator/device (never claim a visual review without actual screenshot); assess tap targets, hierarchy, states, onboarding, empty/loaded/error and navigation. Keep legacy routes until safe cutover. Avoid CSS-like cosmetic-only changes.

### M20-06 — ACTUAL REASONING/ANALYTICS/RESEARCH
Add exact read-only analytic tools for period/month, received/spent vs profit, outstanding debt, expenses by category, clients/projects, growth, incomplete financial coverage and task/case work. Let LLM discuss results critically but never invent cash flow/ROI. Provide period, currency, source and caveats. External web/search plugin/provider tools optional only with real permissions, cite source URLs and timestamps; do not fake internet access. No automatic shopping/payments.

Acceptance: “Руслану подарок завтра?” finds verified context or asks one essential question, offers relevant options and optional reminder, no forced write. “У меня в этом месяце плохой бизнес?” performs actual available data analysis, distinguishes incompleteness, gives evidence-based critique and next steps.

### M20-07 — HELPFUL PROACTIVITY
Build attention engine grounded in existing dates, open cases, unresolved questions, debt deadlines, tasks; opt-in briefing/notifications, quiet hours, dedup, dismiss/snooze, user control and evidence. No always-on microphone; no unexpected sensitive push on locked screen. Failure/restart lifecycle and timezones tested.

### M20-08 — COMPLETE NATIVE VALIDATION
Use synthetic DB and allowed Gemini API when configured; never real personal data in probes. Run full JVM/Android tests, lint, migration and UI tests, crash recovery, idempotence, same-name people, balance, multi-turn AZ/RU/mixed, consent revocation, provider-adapter substitution, first install vs upgrade, memory/case provenance, offline behavior. Standard baseline from apps/android: gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug. Do not run instrumentation on owner's personal device if it could uninstall/reset it. Build/install -r only if safe and signing matches. QA must show user-facing behavior and persistence, not merely compilation.

### M20-09 — PRESERVE REAL FUTURE BACKEND
Native/local-first is required until owner accepts all native functionality at MEM-14.09. Design stable domain API, server migratability, guest→account transition, durable UUIDs, tenant/user/scope permissions, outbox/idempotence, encrypted export/backup, session/device binding now. REAL user signup/login/sync/server AI gateway, multi-device and iOS/web belong to authorized server phase after owner acceptance; do NOT claim server registration before server exists. No unapproved deployment or paid infrastructure.

## CROSS-SCENARIO END TO END TESTS — MUST NOT BE FAKED

1. Casual chat jokes/hello/advice: no forced tool call, natural tone.
2. Gift Ruslan: relevant inquiry, memory lookup if consented, optional current web verification, one useful followup, no fake purchase.
3. Criticize business using real synthetic data: correct totals and coverage; not just generic motivational text.
4. Elchin/BMW 300/100/Friday: one case, stable contact, source, payment/debt semantics, next action, restart retrieval.
5. Same-name two people: no accidental merged finances or tasks.
6. People card rich timeline displays one canonical transaction on all relevant screens without duplicates.
7. Conversation remembered weeks later via source-backed episodic summary, deletion propagates.
8. New install blank & onboarding; APK update preserved; reinstall/backup states explained and tested.
9. Fake second model adapter executes same tool with identical domain result; no finance rewrite.
10. New Today/Secretary/Cases/Memory screens actually rendered, navigable and accessible with screenshots.
11. Changing finance record updates derived summaries and keeps immutable audit/rollback.
12. No cloud account, paid provider, web search, or release readiness claim before implemented and approved.

For EACH: test name, fixtures, expected state, actual DB readback, failure behavior, assertions, UI screenshot if relevant, logs with no personally identifiable content. Document partial implementations honestly.

## SHIPPING DISCIPLINE

After every meaningful implementation package: code review, test, commit, diary ACT entry with exact commands/test count, ACTION_LOG concrete operations, TODO current IDs and closed acceptance evidence, push to authorized branch, verify remote SHA. When possible work on feature branch and create clear review handoff. Never force-push or overwrite unrelated changes. Before final report verify git status/branch/remote and rendered APK metadata. Do not replace the app's original scope with a smaller MVP.

The coding assignment must continue through all unblocked verified features. If a real blocker remains (missing credentials, device, owner permission, provider subscription, platform timeout), document it exactly; keep independent work going where safe, and hand off a resumable checkpoint. **No promise that merely leaving a computer running guarantees continuous execution.**

FINAL REPORT: baseline→HEAD links, workstreams finished vs partial, exact source files, tests/probes, screenshot paths, migrations and safety, alternate provider capability, APK, real consent vs mock, pending owner decisions and next executable step. Never say “finished” when only docs, builds or mock screens exist.

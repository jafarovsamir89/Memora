# Production Quality Contract

All targets below are **proposed acceptance thresholds** for validation, not measured results. Define verified SLOs from beta/load tests.

## Core correctness (release blockers)
- Unauthorized cross-tenant reads/writes = 0 in automated and adversarial tests.
- Duplicate capture requests do not cause duplicate ledger, obligations or reminders.
- Financial arithmetic/invariants: 100% deterministic test correctness on supported typed scenarios.
- Every canonical change traceable to source/user action with version history.
- High-risk external actions cannot execute without explicit authorized confirmation.
- Conflicting/ambiguous person, date, money, obligation direction => no confident automatic commitment.
- Deleted content must not leak through search indexes or summaries after deletion completes.
- Language tests cover RU/AZ/EN/code-switching including amounts, names, negation, time and noisy audio.

## Proposed SLOs
- Core API availability target >=99.9% monthly excluding announced maintenance (confirm budget/design).
- 95th percentile text response latency goal <=3 s for simple queries under baseline load, excluding queue/third-party outages.
- 95th percentile capture acknowledgement <=1 s with healthy network; async processing displays progress rather than promising exact duration.
- Reminder scheduling correctness >=99.99% in deterministic tests; measure delivered vs submitted, acknowledging mobile push networks cannot guarantee receipt.
- Zero data loss in documented restore tests within defined RPO/RTO; RPO/RTO set before production and tested.
- WCAG 2.2 AA-inspired accessibility assessment for web and platform accessibility testing for mobile, with known deviations documented.

## Evaluation suites
Unit tests, property-based financial ledger tests, time zone/DST tests, voice multilingual benchmarks, structured extraction golden cases, prompt-injection red teaming, RAG hallucination tests, approval-gate tests, tenancy fuzzing, offline-sync race tests, backup restore drills, mobile battery/performance tests, privacy/delete/export tests, subscription reconciliation.

## Provider quality gates
Compare WER plus critical-slot accuracy, confusion of «я должен/мне должны», exact entity matching, false positive actions, correct abstentions, cost per successfully handled message, latency and fallback. Provider changes require regression proof and staged rollout.

## Acceptance scenarios
Refer to docs/SCENARIOS.md, every scenario maps to automated E2E fixture with expected stored domain events and verified UI states. No «PASS» based solely on mocked API responses for integrations or real-device constraints; distinguish simulated / staging / real validation.

## Release gating
Security review, privacy/legal checklist, data migration tests, performance/load reports, SLO dashboard, incident runbook, app-store compliance, functioning export and deletion, disaster recovery validation. Failing invariant blocks production release regardless of feature count.

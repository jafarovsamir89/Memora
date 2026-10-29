# Instructions for future agents working on Memora AI

1. Treat README.md and docs/* as **target full-production product vision**. Do not present requirements as implemented software, and do not quietly strip modules to a small MVP.
2. Core identity: independent AI secretary, user-owned long-term accountable memory, voice/text input, finances/debts, tasks, people/projects, calendar, proactive briefings, multi-language RU/AZ/EN.
3. Never invent transaction data or answer personal-history queries without supporting stored evidence. Preserve provenance, temporal semantics, entity disambiguation, exact decimal money, idempotency, and user correction.
4. Privacy and permissions are non-negotiable: no hidden mic/WhatsApp snooping, no unauthorized external actions, protect all tenant boundaries, never expose third-party or user personal data to unrelated users.
5. Before writing production code, provide ADRs on architecture, synchronization, auth, ledger, retention, vendor routing and AI tool permissions, then tests and acceptance mapping. Avoid vendor lock-in.
6. Optimize for quality and reliability first, then cost; provider price/performance assumptions must be verified with real tests and current official documentation.
7. All generated features need error states, offline behavior, accessibility, i18n, source links, revision tracking, threat model and measurable acceptance criteria.
8. Do not assert production readiness from unit tests alone. Stage real-device, backend and reliability validations separately.
9. Keep documentation coherent: changes to domain behavior update PRODUCT SPEC, DATA, MEMORY, UX, TESTS and SCENARIOS as needed.
10. Do not delete user or repository content without explicit direction. Document unresolved assumptions rather than inventing answers.

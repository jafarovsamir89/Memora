# Security, Privacy and Human Control

## Threat model
Sensitive personal finances, debts, third-party names, private speech and documents; attack vectors include stolen devices/tokens, prompt injection, cross-tenant leakage, insecure media URLs, compromised integrations, malicious attachments, insider misuse, replay, data poisoning and billing abuse.

## Mandatory defenses
MFA/passkeys as supported; device session revocation; OAuth scopes and rotation; TLS in transit, encryption at rest with managed keys; short-lived signed attachment URLs; secrets only server-side; object malware checks; strong tenant/space authorization on all queries and storage; idempotency for writes; rate limits and fraud detection.

## Privacy by design
Microphone opt-in with visible recording state. No stealth monitoring. Avoid sending more user context than needed to model providers. Per-provider disclosures, retention policies, contractual review, residency and transfers subject to applicable laws. Original audio deletion configurable; user-accessible history, export and deletion. Shared workspaces require explicit boundaries and invitation.

## AI safety
Untrusted documents/recordings may contain prompt injection; no execution of instructions from imported content. Tools behind deterministic permission checks. No autonomous money transfers, deletion, third-party messaging or external appointment changes without scoped user authorization and confirmation. Ambiguous financial entries enter approval queue.

## Compliance and governance
Before launch, conduct jurisdiction-specific analysis (Azerbaijan, EU/EEA and any target markets) for privacy, consent, recording laws, consumer subscription rules, payment terms and cross-border transfers. Do not claim GDPR certification or regulatory approval without evidence. Data processing agreements, incident response, DSAR workflow, subprocessor register, retention schedule, security contact and vulnerability disclosure necessary.

## Operational policies
Audit sensitive access without storing secrets/transcripts in logs. Access to production personal records restricted, monitored and justified; operator-facing diagnostics redacted. Backups encrypted; restores and deletion cascade tested. Incident classification, escalation, breach-response timing set by legal obligations. Threat models updated for each integration.

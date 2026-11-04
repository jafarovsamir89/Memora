# ADR-016: invalidate episode summaries after profile-fact correction

**Status:** implementation decision for the local Android phase, 2026-11-03. **Scope:** M20-04. Related: [ADR-013](ADR-013-episodic-memory.md), [ADR-006](ADR-006-retention-and-deletion.md), [MEMORY_AND_TRUST](../MEMORY_AND_TRUST.md).

## Context

Profile facts are editable, versioned statements with links to the user turns that supported them. Episodic summaries are separate derived records linked to source turns. A later owner correction can make a summary that cites the superseded turn misleading, even though the original conversation remains retained and immutable.

## Decision

When an explicit profile-fact correction or manual edit changes the fact value/category, invalidate any derived episode that cites any prior source turn attached to that fact, in the same Room transaction as the profile revision. Keep the original conversation turns, the corrected source turn and the complete profile-fact revision/source history. Unrelated episodes remain available. A no-op revision does not invalidate summaries.

This is conservative: an episode that also summarizes another topic from the same source turn is removed as a whole. It is safer to ask the model to rebuild a summary from retained sources than to serve a possibly outdated derivative. It does not rewrite summaries automatically, infer a new fact, or delete conversation history. Users may explicitly create a new episode using retained turns and the correction.

## Consequences

- Correction propagation is local, deterministic, scoped and atomic; no embedding/index cache or provider call is involved.
- Profile revision history and source turns remain available for audit and a future re-summary.
- Deleting the derived episode does not delete its source dialogue.
- Broader entity-wide correction propagation across cases, projects and domain records remains separate work; this ADR covers only derived conversation episodes linked to profile-fact source turns.

## Acceptance

Synthetic repository tests prove that correcting a profile fact removes summaries citing prior supporting turns, preserves unrelated summaries and all source turns, and retains the new profile revision. Existing source-turn deletion/retention behavior and full suite remain green. This is host evidence, not physical-device or field acceptance.

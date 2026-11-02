package ai.memora.app.agent

/** Keep a tool response below the runtime JSON limit without changing full-ledger totals. */
internal fun boundedFinanceEvidence(metadata: Map<String, Any?>, candidates: List<Map<String, Any?>>, matchingCount: Int): Map<String, Any?> {
    val retained = candidates.toMutableList()
    fun response(): Map<String, Any?> = metadata + mapOf(
        "events" to retained.toList(), "count" to retained.size,
        "matching_event_count" to matchingCount, "truncated" to (matchingCount > retained.size),
    )
    var result = response()
    while (retained.isNotEmpty() && AgentCanonical.bytes(result).size > 10_000) {
        retained.removeAt(retained.lastIndex)
        result = response()
    }
    check(AgentCanonical.bytes(result).size <= 10_000) { "finance_evidence_metadata_too_large" }
    return result
}

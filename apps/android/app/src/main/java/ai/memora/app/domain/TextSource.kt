package ai.memora.app.domain

import java.time.Instant

/** Experimental pure model for synthetic source text; no persistence or access control. */
data class TextRevision(val id: String, val number: Long, val text: String, val recordedAt: Instant)
data class TextSource(val id: String, val scopeId: String, val revisions: List<TextRevision>)
data class TextEvidence(val revisionId: String, val startUtf16: Int, val endUtf16: Int)

sealed interface SourceChange {
    data class Changed(val source: TextSource) : SourceChange
    data object Invalid : SourceChange
    data object Conflict : SourceChange
    data object Unchanged : SourceChange
}

/** Evidence indexes are UTF-16 offsets, end-exclusive, and cannot split a surrogate pair. */
object TextSourceRules {
    private const val MAX_TEXT_UNITS = 20_000

    fun validDraft(text: String) = text.length <= MAX_TEXT_UNITS && wellFormed(text)
    private fun validText(text: String) = text.isNotBlank() && validDraft(text)

    private fun wellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i++
            }
        }
        return true
    }

    fun create(id: String, scopeId: String, revisionId: String, text: String, recordedAt: Instant): SourceChange {
        if (listOf(id, scopeId, revisionId).any { it.isBlank() } || !validText(text)) return SourceChange.Invalid
        return SourceChange.Changed(TextSource(id, scopeId, listOf(TextRevision(revisionId, 1, text, recordedAt))))
    }

    fun revise(source: TextSource, expectedVersion: Long, revisionId: String, text: String, recordedAt: Instant): SourceChange {
        if (!validSource(source) || revisionId.isBlank() || !validText(text)) return SourceChange.Invalid
        val previous = source.revisions.last()
        if (expectedVersion != previous.number) return SourceChange.Conflict
        if (source.revisions.any { it.id == revisionId }) return SourceChange.Conflict
        if (text == previous.text) return SourceChange.Unchanged
        // Device wall clocks can be corrected backwards; version, not time, orders revisions.
        if (previous.number == Long.MAX_VALUE) return SourceChange.Invalid
        return SourceChange.Changed(source.copy(revisions = source.revisions + TextRevision(revisionId, previous.number + 1, text, recordedAt)))
    }

    fun quote(source: TextSource, evidence: TextEvidence): String? {
        if (!validSource(source)) return null
        val text = source.revisions.singleOrNull { it.id == evidence.revisionId }?.text ?: return null
        val start = evidence.startUtf16
        val end = evidence.endUtf16
        if (start < 0 || end > text.length || start >= end) return null
        fun boundary(offset: Int) = offset == 0 || offset == text.length ||
            !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
        return if (boundary(start) && boundary(end)) text.substring(start, end) else null
    }

    private fun validSource(source: TextSource): Boolean =
        source.id.isNotBlank() && source.scopeId.isNotBlank() && source.revisions.isNotEmpty() &&
            source.revisions.map { it.id }.toSet().size == source.revisions.size &&
            source.revisions.withIndex().all { (index, revision) ->
                revision.id.isNotBlank() && revision.number == index.toLong() + 1 && validText(revision.text)
            }
}

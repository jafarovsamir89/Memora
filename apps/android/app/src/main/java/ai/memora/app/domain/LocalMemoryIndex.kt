package ai.memora.app.domain

import ai.memora.app.data.CaptureEntry
import java.text.Normalizer
import java.util.Locale

data class MemoryMatch(val entry: CaptureEntry, val evidence: TextEvidence)

/** Local literal retrieval only. No generated answers, stemming, Turkish folding or invented facts. */
object LocalMemoryIndex {
    private val az = Locale.forLanguageTag("az-AZ")
    private fun key(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(az)

    fun search(entries: List<CaptureEntry>, query: String, includeArchived: Boolean = false): List<MemoryMatch> {
        val terms = key(query.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.asReversed().filter { includeArchived || !it.archived }.mapNotNull { entry ->
            val latest = entry.source.revisions.last()
            val searchable = key(latest.text)
            if (terms.all(searchable::contains)) MemoryMatch(entry, TextEvidence(latest.id, 0, latest.text.length)) else null
        }
    }
}

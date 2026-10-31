package ai.memora.app.domain

/** Narrow, evidence-preserving recognition of an explicitly stated present debt. */
object VoiceDebtRules {
    data class DebtStatement(val person: String, val minor: Long)
    private const val NAME = "([\\p{L}][\\p{L}-]{1,48}?)"
    private const val SUFFIX = "(?:nın|nin|nun|nün|ın|in|un|ün)"
    private const val PREFIX = "(?:salam\\s*[,!.]?\\s*)?"
    private val question = Regex("(?iu)^\\s*$PREFIX$NAME$SUFFIX\\s+(?:mənə|mene)\\s+(?:nə\\s*qədər|ne\\s*qeder|neged[əe]|nəqədər)\\s+(?:pul\\s+)?borcu\\s+var\\s*[?.!]?\\s*$")
    private val statement = Regex("(?iu)^\\s*$PREFIX$NAME$SUFFIX\\s+(?:mənə|mene)\\s+([0-9]+(?:[.,][0-9]{1,2})?|yüz|yuz)\\s*(?:manat|azn)\\s+borcu\\s+var\\s*[.!]?\\s*$")
    private val russianQuestion = Regex("(?iu)^\\s*(?:привет\\s*[,!.]?\\s*)?сколько\\s+$NAME\\s+мне\\s+долж(?:ен|на)\\s*[?.!]?\\s*$")

    fun questionPerson(text: String): String? = question.matchEntire(text)?.groupValues?.get(1)
        ?: russianQuestion.matchEntire(text)?.groupValues?.get(1)
    fun statement(text: String): DebtStatement? {
        val match = statement.matchEntire(text) ?: return null
        val amount = when (match.groupValues[2].lowercase()) {
            "yüz", "yuz" -> 10_000L
            else -> VoiceMoneyRules.parseMinor(match.groupValues[2]) ?: return null
        }
        return DebtStatement(match.groupValues[1], amount)
    }
}

package ai.memora.app.agent

import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Supplies the model with an app-derived date without disclosing the profile's time-zone identifier. */
internal object AgentDateContext {
    fun currentLocalDate(profileTimeZoneId: String?, clock: Clock = Clock.systemUTC()): String {
        val zone = runCatching {
            profileTimeZoneId?.takeIf(String::isNotBlank)?.let(ZoneId::of) ?: ZoneId.of("Asia/Baku")
        }.getOrDefault(ZoneId.of("Asia/Baku"))
        return LocalDate.now(clock.withZone(zone)).toString()
    }

    fun promptInstruction(authoritativeLocalDate: String?): String {
        val date = authoritativeLocalDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return """

                No verified current date was supplied by the app. Do not guess a year or resolve relative dates from model memory; ask one short clarification before any dated write.
            """.trimIndent()
        return """

            AUTHORITATIVE DATE CONTEXT: The app's current local calendar date is $date. This date is derived locally from the device clock and the user's profile time zone; it is authoritative. Use it to resolve relative dates. Do not use a year inferred from model knowledge or an assumed current date. Preserve a year the user explicitly states. If a date without a year could reasonably refer to either a past or future occurrence, ask a short clarification before writing. If the user gives no due date, omit due_local; never invent a date or time.
        """.trimIndent()
    }
}

/** Resolves only unambiguous dates stated in the current utterance; model-produced years are never evidence. */
internal object TaskDateIntent {
    fun resolveSingleDate(text: String, today: LocalDate): LocalDate? {
        val normalized = normalize(text)
        val dates = linkedSetOf<LocalDate>()
        RELATIVE_DATES.findAll(normalized).forEach { match ->
            when (match.value) {
                "today", "bu gun", "bugun", "сегодня" -> dates += today
                "tomorrow", "sabah", "завтра" -> dates += today.plusDays(1)
                "day after tomorrow", "послезавтра", "birigun", "birisi gun" -> dates += today.plusDays(2)
                "yesterday", "dun", "dunen", "вчера" -> dates += today.minusDays(1)
            }
        }
        dates += MemoryTemporalQuery.parse(text, today).dates
        // Do not mistake a year mentioned in a task title for its due year. An explicit
        // relative day or qualified weekday remains a deterministic date instruction.
        if (dates.isEmpty() && hasExplicitYear(text)) return null
        return dates.singleOrNull()
    }

    fun hasExplicitYear(text: String): Boolean = EXPLICIT_YEAR.containsMatchIn(text)

    fun explicitlyRefersToPastYear(text: String): Boolean = PAST_YEAR.containsMatchIn(normalize(text))

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(MARKS, "").replace('ə', 'e').replace('ı', 'i').replace('ё', 'е')
        .replace(WHITESPACE, " ").trim()

    private val EXPLICIT_YEAR = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val RELATIVE_DATES = Regex(
        "(?<![\\p{L}\\p{N}])(?:day after tomorrow|послезавтра|birisi gun|birigun|tomorrow|yesterday|today|sabah|bugun|bu gun|dunen|dun|сегодня|завтра|вчера)(?![\\p{L}\\p{N}])",
    )
    private val PAST_YEAR = Regex(
        "(?<![\\p{L}\\p{N}])(?:last year|previous year|kecen il|oten il|в прошлом году|прошлом году)(?![\\p{L}\\p{N}])",
    )
    private val MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")
}

package ai.memora.app.agent

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Conservative, deterministic calendar phrases used only to filter local memory search. */
internal data class MemoryTemporalQuery(
    val dates: Set<LocalDate>,
    val lexicalQuery: String,
    val invalidRange: Boolean = false,
) {
    companion object {
        fun parse(query: String, today: LocalDate): MemoryTemporalQuery {
            var remaining = normalize(query)
            val dates = linkedSetOf<LocalDate>()

            val rangeMatch = DATE_RANGES.firstNotNullOfOrNull { it.find(remaining) }
            if (rangeMatch != null) {
                val match = rangeMatch
                val start = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
                val end = runCatching { LocalDate.parse(match.groupValues[2]) }.getOrNull()
                remaining = remaining.replaceRange(match.range, " ")
                if (start == null || end == null) {
                    return MemoryTemporalQuery(emptySet(), remaining.replace(WHITESPACE, " ").trim(), invalidRange = true)
                }
                val startDate = requireNotNull(start)
                val endDate = requireNotNull(end)
                val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
                if (days <= 0 || days > MAX_RANGE_DAYS) {
                    return MemoryTemporalQuery(emptySet(), remaining.replace(WHITESPACE, " ").trim(), invalidRange = true)
                }
                var date: LocalDate = startDate
                while (!date.isAfter(endDate)) {
                    dates += date
                    date = date.plusDays(1)
                }
            }

            WEEK_RANGES.forEach { (phrase, weekOffset) ->
                val regex = phraseRegex(phrase)
                if (regex.containsMatchIn(remaining)) {
                    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
                    (0L..6L).forEach { dates += monday.plusDays(it) }
                    remaining = regex.replace(remaining, " ")
                }
            }

            WEEKDAYS.forEach { (alias, weekday) ->
                WEEKDAY_MODIFIERS.forEach { (phrase, mode) ->
                    val regex = phraseRegex(phrase.replace("{day}", alias))
                    if (regex.containsMatchIn(remaining)) {
                        val date = when (mode) {
                            WeekdayMode.PREVIOUS -> today.with(TemporalAdjusters.previous(weekday))
                            WeekdayMode.THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                .with(TemporalAdjusters.nextOrSame(weekday))
                            WeekdayMode.NEXT -> today.with(TemporalAdjusters.next(weekday))
                        }
                        dates += date
                        remaining = regex.replace(remaining, " ")
                    }
                }
            }

            return MemoryTemporalQuery(dates, remaining.replace(WHITESPACE, " ").trim())
        }

        private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "").replace('ə', 'e').replace('ı', 'i').replace('ё', 'е')
            .replace(WHITESPACE, " ").trim()

        private fun phraseRegex(phrase: String) = Regex(
            "(?<![\\p{L}\\p{N}])${Regex.escape(normalize(phrase))}(?![\\p{L}\\p{N}])",
        )

        private enum class WeekdayMode { PREVIOUS, THIS_WEEK, NEXT }

        private const val MAX_RANGE_DAYS = 366L
        private const val ISO_DATE = "(\\d{4}-\\d{2}-\\d{2})"
        private val DATE_RANGES = listOf(
            Regex("(?<![\\p{L}\\p{N}])between\\s+$ISO_DATE\\s+and\\s+$ISO_DATE(?![\\p{L}\\p{N}])"),
            Regex("(?<![\\p{L}\\p{N}])from\\s+$ISO_DATE\\s+to\\s+$ISO_DATE(?![\\p{L}\\p{N}])"),
            Regex("(?<![\\p{L}\\p{N}])с\\s+$ISO_DATE\\s+по\\s+$ISO_DATE(?![\\p{L}\\p{N}])"),
            Regex("(?<![\\p{L}\\p{N}])между\\s+$ISO_DATE\\s+и\\s+$ISO_DATE(?![\\p{L}\\p{N}])"),
            Regex("(?<![\\p{L}\\p{N}])$ISO_DATE\\s+ile\\s+$ISO_DATE\\s+arasinda(?![\\p{L}\\p{N}])"),
            Regex("(?<!\\d)$ISO_DATE\\s*(?:\\.\\.|–|—)\\s*$ISO_DATE(?!\\d)"),
        )

        // Week ranges use ISO weeks (Monday through Sunday). Relative weekday phrases are
        // intentionally explicit: an unqualified weekday is ambiguous between past and future.
        private val WEEK_RANGES = listOf(
            "last week" to -1, "this week" to 0, "next week" to 1,
            "kecen hefte" to -1, "bu hefte" to 0, "gelecek hefte" to 1,
            "на прошлой неделе" to -1, "прошлая неделя" to -1,
            "на этой неделе" to 0, "эта неделя" to 0,
            "на следующей неделе" to 1, "следующая неделя" to 1,
        )

        private val WEEKDAYS = listOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY,
            "bazar ertesi" to DayOfWeek.MONDAY, "cersenbe axsami" to DayOfWeek.TUESDAY,
            "cersenbe" to DayOfWeek.WEDNESDAY, "cume axsami" to DayOfWeek.THURSDAY,
            "cume" to DayOfWeek.FRIDAY, "senbe" to DayOfWeek.SATURDAY, "bazar" to DayOfWeek.SUNDAY,
            "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "среда" to DayOfWeek.WEDNESDAY,
            "четверг" to DayOfWeek.THURSDAY, "пятница" to DayOfWeek.FRIDAY, "суббота" to DayOfWeek.SATURDAY,
            "пятницу" to DayOfWeek.FRIDAY,
            "воскресенье" to DayOfWeek.SUNDAY,
        ).sortedByDescending { it.first.length }

        private val WEEKDAY_MODIFIERS = listOf(
            "last {day}" to WeekdayMode.PREVIOUS, "previous {day}" to WeekdayMode.PREVIOUS,
            "this {day}" to WeekdayMode.THIS_WEEK, "next {day}" to WeekdayMode.NEXT,
            "kecen {day}" to WeekdayMode.PREVIOUS, "kechen {day}" to WeekdayMode.PREVIOUS,
            "bu {day}" to WeekdayMode.THIS_WEEK, "gelecek {day}" to WeekdayMode.NEXT,
            "прошлый {day}" to WeekdayMode.PREVIOUS, "прошлая {day}" to WeekdayMode.PREVIOUS,
            "прошлую {day}" to WeekdayMode.PREVIOUS,
            "прошлой {day}" to WeekdayMode.PREVIOUS,
            "в прошлый {day}" to WeekdayMode.PREVIOUS, "в прошлую {day}" to WeekdayMode.PREVIOUS,
            "эту {day}" to WeekdayMode.THIS_WEEK, "в эту {day}" to WeekdayMode.THIS_WEEK,
            "следующий {day}" to WeekdayMode.NEXT, "следующую {day}" to WeekdayMode.NEXT,
            "в следующий {day}" to WeekdayMode.NEXT, "в следующую {day}" to WeekdayMode.NEXT,
        )

        private val MARKS = Regex("\\p{M}+")
        private val WHITESPACE = Regex("\\s+")
    }
}

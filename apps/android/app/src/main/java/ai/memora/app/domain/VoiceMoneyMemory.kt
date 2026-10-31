package ai.memora.app.domain

import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** Money received from a person is an observed transfer, not automatically income or a debt. */
data class VoicePerson(val id: String, val name: String)
data class VoiceReceipt(
    val id: String, val commandId: String, val personId: String, val minorUnits: Long,
    val currency: String, val source: String, val recordedAt: Instant, val occurredAt: Instant? = null,
)
data class VoiceDebt(val id: String, val commandId: String, val personId: String, val minorUnits: Long,
    val currency: String, val source: String, val recordedAt: Instant)
data class VoiceMoneySnapshot(val persons: List<VoicePerson> = emptyList(), val receipts: List<VoiceReceipt> = emptyList(),
    val debts: List<VoiceDebt> = emptyList())
data class VoiceReceiptCommand(
    val commandId: String, val personId: String, val receiptId: String, val personName: String,
    val amount: String, val source: String, val recordedAt: Instant, val occurredAt: Instant? = null,
)

sealed interface VoiceMemoryChange {
    data class Applied(val snapshot: VoiceMoneySnapshot) : VoiceMemoryChange
    data object Duplicate : VoiceMemoryChange
    data object Invalid : VoiceMemoryChange
    data object Ambiguous : VoiceMemoryChange
    data object Conflict : VoiceMemoryChange
}

sealed interface VoiceMoneyAnswer {
    data class Found(val person: VoicePerson, val receipts: List<VoiceReceipt>, val totalMinor: Long) : VoiceMoneyAnswer
    data object Missing : VoiceMoneyAnswer
    data object Ambiguous : VoiceMoneyAnswer
    data object Overflow : VoiceMoneyAnswer
}

/** Deterministic evidence checks keep the AI's proposed data within the spoken source. */
object VoiceMoneyRules {
    private val az = Locale.forLanguageTag("az-AZ")
    fun key(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(az)
    private val interrogative = Regex("(?iu)(?:\\?|(?<!\\p{L})(?:nə|ne|qədər|qeder|сколько|когда|кто|нибудь|не)(?!\\p{L}))")
    private val denied = Regex("(?iu)(?<!\\p{L})(?:deyil|değil|yox|vermədi|vermedi|almadım|almadim|не|нет)(?!\\p{L})")
    private val receivedVerb = Regex("(?iu)(?<!\\p{L})(?:verdi|verib|vermişdi|vermisdi|передал|передала|дал|дала)(?!\\p{L})")
    private val recipient = Regex("(?iu)(?<!\\p{L})(?:mənə|mene|мне)(?!\\p{L})")
    private val money = Regex("(?iu)(?<![\\p{L}\\p{N}])([0-9]+(?:[.,][0-9]{1,2})?|yüz|yuz)\\s*(?:manat|azn|манат|маната|манатов)(?!\\p{L})")

    fun parseMinor(value: String): Long? {
        if (value.equals("yüz", true) || value.equals("yuz", true)) return 10_000L
        if (!Regex("[0-9]{1,14}(?:[.,][0-9]{1,2})?").matches(value)) return null
        return runCatching { BigDecimal(value.replace(',', '.')).movePointRight(2).longValueExact() }
            .getOrNull()?.takeIf { it > 0 }
    }

    fun save(state: VoiceMoneySnapshot, command: VoiceReceiptCommand): VoiceMemoryChange {
        if (listOf(command.commandId, command.personId, command.receiptId, command.personName).any { it.isBlank() || it.length > 100 } ||
            command.source.isBlank() || command.source.length > 20_000) return VoiceMemoryChange.Invalid
        val amount = parseMinor(command.amount) ?: return VoiceMemoryChange.Invalid
        val source = key(command.source)
        val person = key(command.personName)
        val amounts = money.findAll(source).map { parseMinor(it.groupValues[1]) }.toList()
        val quantity = "(?:[0-9]+(?:[.,][0-9]{1,2})?|yüz|yuz)\\s*(?:manat|azn|манат|маната|манатов)"
        val verb = "(?:verdi|verib|vermişdi|vermisdi|передал|передала|дал|дала)"
        val simpleStatement = Regex("(?iu)^\\s*${Regex.escape(person)}\\s+(?:(?:mənə|mene|мне)\\s+$quantity\\s+$verb|$verb\\s+мне\\s+$quantity)[.!]?\\s*$")
        if (command.occurredAt != null || !Regex("(?<!\\p{L})${Regex.escape(person)}(?!\\p{L})").containsMatchIn(source) ||
            !simpleStatement.matches(source) ||
            interrogative.containsMatchIn(source) || denied.containsMatchIn(source) ||
            !receivedVerb.containsMatchIn(source) || !recipient.containsMatchIn(source) ||
            amounts.size != 1 || amounts.single() != amount) return VoiceMemoryChange.Invalid
        val matches = state.persons.filter { key(it.name) == person }
        if (matches.size > 1) return VoiceMemoryChange.Ambiguous
        val existing = matches.singleOrNull()
        val resolved = existing ?: VoicePerson(command.personId, command.personName.trim())
        val receipt = VoiceReceipt(command.receiptId, command.commandId, resolved.id, amount, "AZN",
            command.source, command.recordedAt, command.occurredAt)
        state.receipts.find { it.commandId == command.commandId }?.let {
            return if (it == receipt) VoiceMemoryChange.Duplicate else VoiceMemoryChange.Conflict
        }
        if (state.receipts.any { it.id == command.receiptId } || state.debts.any { it.id == command.receiptId || it.commandId == command.commandId } ||
            (existing == null && state.persons.any { it.id == command.personId })) return VoiceMemoryChange.Conflict
        return VoiceMemoryChange.Applied(state.copy(
            persons = if (existing == null) state.persons + resolved else state.persons,
            receipts = state.receipts + receipt,
        ))
    }

    fun received(state: VoiceMoneySnapshot, name: String): VoiceMoneyAnswer {
        val matches = state.persons.filter { key(it.name) == key(name) }
        if (matches.isEmpty()) return VoiceMoneyAnswer.Missing
        if (matches.size != 1) return VoiceMoneyAnswer.Ambiguous
        val receipts = state.receipts.filter { it.personId == matches.single().id && it.currency == "AZN" }
        if (receipts.isEmpty()) return VoiceMoneyAnswer.Missing
        val sum = try { receipts.fold(0L) { acc, receipt -> Math.addExact(acc, receipt.minorUnits) } }
            catch (_: ArithmeticException) { return VoiceMoneyAnswer.Overflow }
        return VoiceMoneyAnswer.Found(matches.single(), receipts, sum)
    }
}

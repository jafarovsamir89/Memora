package ai.memora.app.acceptance

import ai.memora.app.data.CaptureRead
import ai.memora.app.data.CaptureWrite
import ai.memora.app.data.EncryptedCaptureStore
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.StoreRead
import ai.memora.app.data.StoreWrite
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.CalendarPersonLinkRepository
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.LocalProfileRow
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.data.protectedstore.PersonAliasRow
import ai.memora.app.data.protectedstore.PersonAliasRevisionRow
import ai.memora.app.data.protectedstore.ProfileFactRow
import ai.memora.app.data.protectedstore.ProfileFactRevisionRow
import ai.memora.app.data.protectedstore.ProfileFactSourceRow
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedProfileMemoryRepository
import ai.memora.app.data.protectedstore.TaskEventRow
import ai.memora.app.domain.TaskDecision
import ai.memora.app.domain.TaskRules
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import javax.crypto.SecretKey

/** Stable IDs for the synthetic reliability profile. None are used by the production app path. */
internal object SyntheticReliabilityIds {
    const val scope = "00000000-0000-4000-8000-000000026201"
    const val foreignScope = "00000000-0000-4000-8000-000000026202"
    const val profile = "00000000-0000-4000-8000-000000026203"
    const val personElchin = "00000000-0000-4000-8000-000000026204"
    const val personElchinLookalike = "00000000-0000-4000-8000-000000026205"
    const val personForeign = "00000000-0000-4000-8000-000000026206"
    const val financePerson = "00000000-0000-4000-8000-000000026207"
    const val financeIdentityLink = "00000000-0000-4000-8000-000000026208"
    const val projectBmw = "00000000-0000-4000-8000-000000026209"
    const val noteSource = "00000000-0000-4000-8000-000000026210"
    const val projectNoteLink = "00000000-0000-4000-8000-000000026211"
    const val caseBmw = "00000000-0000-4000-8000-000000026212"
    const val caseParticipant = "00000000-0000-4000-8000-000000026213"
    const val caseProjectEvidence = "00000000-0000-4000-8000-000000026214"
    const val caseFinanceEvidence = "00000000-0000-4000-8000-000000026215"
    const val financeEvent = "00000000-0000-4000-8000-000000026216"
    const val roomTask = "00000000-0000-4000-8000-000000026217"
    const val taskPersonLink = "00000000-0000-4000-8000-000000026218"
    const val calendarEvent = "00000000-0000-4000-8000-000000026219"
    const val calendarPersonLink = "00000000-0000-4000-8000-000000026220"
    const val conversationTurn = "00000000-0000-4000-8000-000000026221"
    const val editableTable = "00000000-0000-4000-8000-000000026222"
    const val editableTableRow = "00000000-0000-4000-8000-000000026223"
    const val inboxCommand = "00000000-0000-4000-8000-000000026224"
    const val inboxSource = "00000000-0000-4000-8000-000000026225"
    const val inboxRevision = "00000000-0000-4000-8000-000000026226"
    const val legacyTask = "00000000-0000-4000-8000-000000026227"
    const val personRuslan = "00000000-0000-4000-8000-000000026228"
    const val personLeyla = "00000000-0000-4000-8000-000000026229"
    const val financePersonRuslan = "00000000-0000-4000-8000-000000026230"
    const val financeReceiptElchin = "00000000-0000-4000-8000-000000026231"
    const val financeReceiptRuslan = "00000000-0000-4000-8000-000000026232"
    const val settlementEvent = "00000000-0000-4000-8000-000000026233"
    const val correctionCommand = "synthetic-command-repayment-correction"
    const val caseReceiptEvidence = "00000000-0000-4000-8000-000000026234"
    const val roomTaskPast = "00000000-0000-4000-8000-000000026235"
    const val calendarPast = "00000000-0000-4000-8000-000000026236"
    const val projectOther = "00000000-0000-4000-8000-000000026237"
    const val noteOther = "00000000-0000-4000-8000-000000026238"
    const val projectOtherLink = "00000000-0000-4000-8000-000000026239"
    const val conversationTurnRu = "00000000-0000-4000-8000-000000026240"
    const val conversationTurnMixed = "00000000-0000-4000-8000-000000026241"
    const val inboxCommandRu = "00000000-0000-4000-8000-000000026242"
    const val inboxSourceRu = "00000000-0000-4000-8000-000000026243"
    const val inboxRevisionRu = "00000000-0000-4000-8000-000000026244"
    const val legacyTaskPast = "00000000-0000-4000-8000-000000026245"
    const val financeRuslanIdentityLink = "00000000-0000-4000-8000-000000026246"
    const val aliasElchinBmw = "00000000-0000-4000-8000-000000026247"
    const val aliasElchinService = "00000000-0000-4000-8000-000000026248"
    const val aliasRuslan = "00000000-0000-4000-8000-000000026249"
    const val factAzerbaijaniPreference = "00000000-0000-4000-8000-000000026250"
    const val factBmwContact = "00000000-0000-4000-8000-000000026251"
}

internal data class SyntheticFinanceInvariant(
    val eventId: String,
    val personId: String,
    val kind: String,
    val minorUnits: Long,
    val currency: String,
)

internal data class SyntheticReliabilitySnapshot(
    val profileId: String,
    val scopeId: String,
    val canonicalPeople: List<Pair<String, String>>,
    val aliases: List<Triple<String, String, String>>,
    val unconfirmedAliasSuggestionsNotPersisted: List<Pair<String, String>>,
    val financeIdentity: Pair<String, String>?,
    val projects: List<String>,
    val notes: List<String>,
    val domainLinks: List<Triple<String, String, String>>,
    val caseIds: List<String>,
    val caseParticipants: List<Pair<String, String>>,
    val caseEvidence: List<Pair<String, String>>,
    val finance: List<SyntheticFinanceInvariant>,
    val debtBalances: List<Pair<String, Long>>,
    val financeAdjustments: List<Triple<String, String, Long>>,
    val roomTasks: List<Pair<String, String?>>,
    val taskPersonLinks: List<Pair<String, String>>,
    val calendarEvents: List<Pair<String, String>>,
    val calendarPersonLinks: List<Pair<String, String>>,
    val conversationTurns: List<String>,
    val userUtterances: List<String>,
    val episodes: List<String>,
    val profileFacts: List<Triple<String, String, String>>,
    val inboxSources: List<Pair<String, String>>,
    val legacyTasks: List<Pair<String, String?>>,
    val editableTableRows: List<Pair<String, String>>,
)

/**
 * Seeds only an explicitly named empty Room database and dedicated temp directory.
 * This helper lives in src/test and cannot be called by a regular app installation.
 */
internal class SyntheticReliabilityProfile(
    private val database: AgentDatabase,
    private val directory: File,
    private val key: SecretKey,
) {
    private val ids = SyntheticReliabilityIds
    private val fixedNow = "2026-11-18T12:00:00Z"
    private val fixedInstant = Instant.parse(fixedNow)
    private val unconfirmedAliasSuggestions = listOf("Elçin servis ustası" to ids.personElchin,
        "R. Əliyev" to ids.personRuslan)
    private val dao get() = database.protectedStoreDao()
    private val inboxFile get() = File(directory, "synthetic-inbox.enc")
    private val taskFile get() = File(directory, "synthetic-tasks.enc")

    init {
        require(directory.name.startsWith("memora-acceptance-synthetic-")) {
            "Synthetic profile requires a dedicated acceptance temp directory"
        }
        require(directory.isDirectory && directory.list()?.isEmpty() == true) {
            "Synthetic profile files must start in an empty dedicated directory"
        }
    }

    suspend fun seed(): SyntheticReliabilitySnapshot {
        require(dao.localProfile() == null && dao.activeObjects(ids.scope).isEmpty() &&
            dao.activeObjects(ids.foreignScope).isEmpty() && dao.people().isEmpty() &&
            dao.moneyEvents().isEmpty() && dao.taskEvents().isEmpty() && dao.calendarEvents(ids.scope).isEmpty()) {
            "Synthetic fixture refuses a non-empty Room database"
        }
        dao.insertLocalProfile(LocalProfileRow(ids.profile, "Synthetic owner", "az", "friendly", "balanced",
            "Asia/Baku", 1, true, null, fixedInstant.toEpochMilli(), fixedInstant.toEpochMilli()))

        val domain = ProtectedDomainRepository(database)
        val cases = ProtectedCaseRepository(database)
        val archive = ProtectedConversationRepository(database)

        domain.createObject(ids.scope, ids.personElchin, DomainKind.PERSON, "Elçin Məmmədov", "Synthetic primary person", null, fixedNow)
        domain.createObject(ids.scope, ids.personElchinLookalike, DomainKind.PERSON, "Elçin Məmmədov", "Distinct synthetic person", null, fixedNow)
        domain.createObject(ids.scope, ids.personRuslan, DomainKind.PERSON, "Ruslan Əliyev", "Synthetic finance counterparty", null, fixedNow)
        domain.createObject(ids.scope, ids.personLeyla, DomainKind.PERSON, "Leyla Həsənova", "Synthetic separate person", null, fixedNow)
        domain.createObject(ids.foreignScope, ids.personForeign, DomainKind.PERSON, "Elçin Məmmədov", "Foreign scope decoy", null, fixedNow)
        seedOwnerConfirmedAlias(ids.aliasElchinBmw, ids.personElchin, "Elçin BMW ustası")
        seedOwnerConfirmedAlias(ids.aliasElchinService, ids.personElchin, "Elçin servis")
        seedOwnerConfirmedAlias(ids.aliasRuslan, ids.personRuslan, "Ruslan")

        domain.createObject(ids.scope, ids.projectBmw, DomainKind.PROJECT, "BMW təmiri", "Synthetic BMW repair project", null, fixedNow)
        domain.createObject(ids.scope, ids.noteSource, DomainKind.NOTE, "Servis qeydi", "Synthetic note source for BMW repair", null, fixedNow)
        domain.createObject(ids.scope, ids.projectOther, DomainKind.PROJECT, "Ev təmiri", "Synthetic unrelated project", null, fixedNow)
        domain.createObject(ids.scope, ids.noteOther, DomainKind.NOTE, "Qarışıq qeyd", "Unrelated synthetic note", null, fixedNow)
        domain.link(ids.scope, ids.projectNoteLink, ids.projectBmw, ids.noteSource, "has_note")
        domain.link(ids.scope, ids.projectOtherLink, ids.projectOther, ids.noteOther, "has_note")

        val finance = domain.recordFinanceEvent(ids.scope, "Elçin Məmmədov", "debt", 30_000, "AZN",
            "synthetic-test-source", "Synthetic test debt only", ids.financePerson, ids.financeEvent,
            "synthetic-command-finance-1", fixedNow, fixedNow)
        check(finance.personId == ids.financePerson && finance.minorUnits == 30_000L && finance.currency == "AZN")
        domain.recordFinanceEvent(ids.scope, "Elçin Məmmədov", "receipt", 10_000, "AZN",
            "synthetic-test-source", "Synthetic partial repayment before correction", ids.financePerson,
            ids.financeReceiptElchin, "synthetic-command-finance-2", fixedNow, fixedNow)
        domain.recordFinanceEvent(ids.scope, "Ruslan Əliyev", "receipt", 15_000, "AZN",
            "synthetic-test-source", "Synthetic standalone receipt", ids.financePersonRuslan,
            ids.financeReceiptRuslan, "synthetic-command-finance-ruslan", fixedNow, fixedNow)
        domain.recordSettlement(ids.scope, ids.settlementEvent, "synthetic-command-settlement", ids.financeEvent,
            10_000, "AZN", fixedNow, "Synthetic partial repayment", ids.financeReceiptElchin)
        domain.correctFinanceEventAmount(ids.scope, ids.financeReceiptElchin, 12_000,
            ids.correctionCommand, "Synthetic corrected repayment amount", fixedNow)
        ProtectedCaseRepository(database).confirmFinancePersonIdentity(ids.scope, ids.financePerson, ids.personElchin,
            linkId = ids.financeIdentityLink, confirmedBy = "owner", now = fixedNow)
        cases.confirmFinancePersonIdentity(ids.scope, ids.financePersonRuslan, ids.personRuslan,
            linkId = ids.financeRuslanIdentityLink, confirmedBy = "owner", now = fixedNow)

        cases.createCase(ids.scope, ids.caseBmw, "BMW təmiri", "Synthetic repair case for acceptance only",
            nextAction = "Yoxlama vaxtını təsdiqlə", sourceTurnId = ids.conversationTurn, now = fixedNow)
        cases.addParticipant(ids.scope, ids.caseBmw, ids.personElchin, role = "owner",
            participantId = ids.caseParticipant, now = fixedNow)
        cases.addEvidence(ids.scope, ids.caseBmw, "domain_object", ids.projectBmw, "project_source",
            evidenceId = ids.caseProjectEvidence, now = fixedNow)
        cases.addEvidence(ids.scope, ids.caseBmw, "money_event", ids.financeEvent, "advance",
            evidenceId = ids.caseFinanceEvidence, now = fixedNow)
        cases.addEvidence(ids.scope, ids.caseBmw, "money_event", ids.financeReceiptElchin, "partial_repayment",
            evidenceId = ids.caseReceiptEvidence, now = fixedNow)

        dao.putTaskEvents(listOf(TaskEventRow(taskId = ids.roomTask, scopeId = ids.scope, actorId = "owner",
            commandId = "synthetic-command-task-room", type = "created", previousVersion = null,
            title = "Servislə danış", completed = false, dueLocal = "2026-11-20T10:00",
            dueZone = "Asia/Baku", remind = false, personLabel = null, projectLabel = null),
            TaskEventRow(taskId = ids.roomTaskPast, scopeId = ids.scope, actorId = "owner",
                commandId = "synthetic-command-task-room-past", type = "created", previousVersion = null,
                title = "Keçmiş sintetik tapşırıq", completed = false, dueLocal = "2026-11-17T10:00",
                dueZone = "Asia/Baku", remind = false, personLabel = null, projectLabel = null)))
        cases.confirmTaskPersonLink(ids.scope, ids.roomTask, ids.personElchin, relation = "for_case",
            linkId = ids.taskPersonLink, confirmedBy = "owner", now = fixedNow)

        domain.createCalendarEvent(CalendarInput(ids.calendarEvent, ids.scope, "BMW yoxlanışı",
            "2026-11-21T11:30", "2026-11-21T12:00", "Asia/Baku", "minute"))
        domain.createCalendarEvent(CalendarInput(ids.calendarPast, ids.scope, "Keçmiş sintetik görüş",
            "2026-11-17T09:00", "2026-11-17T09:30", "Asia/Baku", "minute"))
        CalendarPersonLinkRepository(database).confirm(ids.scope, ids.calendarEvent, ids.personElchin,
            relation = "mechanic", linkId = ids.calendarPersonLink, confirmedBy = "owner", now = fixedNow)

        archive.appendTurn(ids.scope, ids.conversationTurn,
            "Elçin BMW təmiri üçün mənə 300 manat borcludur. Mən azərbaycanca cavabları üstün tuturam.",
            "Synthetic test profile saved the 300 AZN obligation context.",
            createdAtMillis = fixedInstant.toEpochMilli())
        archive.appendTurn(ids.scope, ids.conversationTurnRu,
            "Он сегодня дал 100 манат за ремонт BMW.", "Synthetic Russian turn records the pre-correction repayment.",
            createdAtMillis = fixedInstant.toEpochMilli() + 1)
        archive.appendTurn(ids.scope, ids.conversationTurnMixed,
            "Elçin indi 120 verdi, BMW təmiri üçün.", "Synthetic mixed AZ/RU correction request context.",
            createdAtMillis = fixedInstant.toEpochMilli() + 2)
        archive.saveEpisode(ids.scope, "BMW təmiri", "Synthetic summary backed by two retained source turns.",
            listOf(ids.conversationTurn, ids.conversationTurnRu), fixedInstant.toEpochMilli())
        seedUserStatedFact(ids.factAzerbaijaniPreference, "preference", "Prefers replies in Azerbaijani.", ids.conversationTurn,
            "Elçin BMW təmiri üçün mənə 300 manat borcludur. Mən azərbaycanca cavabları üstün tuturam.",
            "azərbaycanca cavabları üstün tuturam")
        seedUserStatedFact(ids.factBmwContact, "context", "Elçin is the confirmed BMW repair contact.", ids.conversationTurn,
            "Elçin BMW təmiri üçün mənə 300 manat borcludur. Mən azərbaycanca cavabları üstün tuturam.",
            "Elçin BMW təmiri üçün mənə 300 manat borcludur")

        domain.createTable(ids.scope, ids.editableTable, "Synthetic service tracker", "editable", listOf(
            ColumnSpec("title", "Title", ColumnType.TEXT, required = true),
            ColumnSpec("status", "Status", ColumnType.TEXT, required = true),
        ), fixedNow)
        domain.addRows(ids.scope, ids.editableTable, 1, listOf(ids.editableTableRow to mapOf(
            "title" to "BMW service", "status" to "waiting for parts")))

        val taskStore = EncryptedTaskStore(taskFile) { key }
        listOf(ids.legacyTask to "2026-11-19T10:00", ids.legacyTaskPast to "2026-11-17T10:00")
            .forEachIndexed { index, (taskId, due) ->
                val title = if (index == 0) "Call Ruslan tomorrow" else "Past synthetic follow-up"
                val createdTask = TaskRules.create(taskId, ids.scope, "owner", "synthetic-command-legacy-task-$index",
                    title) as? TaskDecision.Changed ?: error("Synthetic task fixture was rejected")
                check(taskStore.append(createdTask.event) == StoreWrite.Recorded)
                val task = ai.memora.app.domain.Task(taskId, ids.scope, title, false, 1)
                val withDue = TaskRules.details(task, 1, "owner", "synthetic-command-legacy-task-details-$index",
                    due, "Asia/Baku", false, if (index == 0) "Ruslan Əliyev" else null, null)
                    as? TaskDecision.Changed ?: error("Synthetic task details were rejected")
                check(taskStore.append(withDue.event) == StoreWrite.Recorded)
            }

        val inbox = EncryptedCaptureStore(inboxFile) { key }
        check(inbox.saveDraft(0, "Synthetic inbox source about BMW service") == CaptureWrite.Recorded)
        check(inbox.submit(ids.inboxCommand, 1, ids.inboxSource, ids.inboxRevision,
            "Synthetic inbox source about BMW service", fixedInstant, "Asia/Baku") == CaptureWrite.Recorded)
        check(inbox.saveDraft(2, "Synthetic RU inbox source about repair") == CaptureWrite.Recorded)
        check(inbox.submit(ids.inboxCommandRu, 3, ids.inboxSourceRu, ids.inboxRevisionRu,
            "Synthetic RU inbox source about repair", fixedInstant.plusSeconds(1), "Asia/Baku") == CaptureWrite.Recorded)

        return snapshot()
    }

    suspend fun snapshot(): SyntheticReliabilitySnapshot {
        val domain = ProtectedDomainRepository(database)
        val archive = ProtectedConversationRepository(database)
        val profileMemory = ProtectedProfileMemoryRepository(database)
        val case = dao.caseRecord(ids.scope, ids.caseBmw)
        val participantRows = dao.caseParticipants(ids.scope, ids.caseBmw).filter { it.deletedAt == null }
        val evidenceRows = dao.caseEvidence(ids.scope, ids.caseBmw).filter { it.deletedAt == null }
        val finance = domain.allFinanceEvents(ids.scope)
        val identity = ProtectedCaseRepository(database).financePersonIdentityChoices(ids.scope, ids.financePerson)
            ?.confirmedLink?.let { it.financePersonId to it.domainPersonId }
        val inboxRead = EncryptedCaptureStore(inboxFile) { key }.load() as? CaptureRead.Ready
            ?: error("Synthetic inbox store unavailable")
        val taskRead = EncryptedTaskStore(taskFile) { key }.load() as? StoreRead.Ready
            ?: error("Synthetic task store unavailable")
        val tables = domain.tables(ids.scope)
        val factRows = profileMemory.list(ids.scope, limit = 20)
        return SyntheticReliabilitySnapshot(
            profileId = dao.localProfile()?.localProfileId ?: error("Synthetic profile missing"),
            scopeId = ids.scope,
            canonicalPeople = dao.activeObjects(ids.scope).filter { it.kind == DomainKind.PERSON.name }
                .map { it.objectId to it.title }.sortedBy { it.first },
            aliases = dao.activePersonAliases(ids.scope).map { Triple(it.aliasId, it.aliasText, it.domainPersonId) }
                .sortedBy { it.first },
            unconfirmedAliasSuggestionsNotPersisted = unconfirmedAliasSuggestions,
            financeIdentity = identity,
            projects = dao.activeObjects(ids.scope).filter { it.kind == DomainKind.PROJECT.name }.map { it.objectId }.sorted(),
            notes = dao.activeObjects(ids.scope).filter { it.kind == DomainKind.NOTE.name }.map { it.objectId }.sorted(),
            domainLinks = dao.activeLinks(ids.scope).map { Triple(it.linkId, it.fromId, it.toId) }.sortedBy { it.first },
            caseIds = listOfNotNull(case?.caseId),
            caseParticipants = participantRows.map { it.caseId to it.domainPersonId }.sortedBy { it.second },
            caseEvidence = evidenceRows.map { it.sourceKind to it.sourceId }.sortedBy { it.second },
            finance = finance.map { SyntheticFinanceInvariant(it.eventId, it.personId, it.kind, it.minorUnits, it.currency) }
                .sortedBy { it.eventId },
            debtBalances = finance.filter { it.kind == "debt" }
                .map { it.eventId to domain.outstandingDebtMinor(ids.scope, it.eventId) }.sortedBy { it.first },
            financeAdjustments = dao.adjustments(ids.scope).map { Triple(it.eventId, it.kind, it.minorUnits) }.sortedBy { it.first },
            roomTasks = dao.taskEvents().filter { it.scopeId == ids.scope }
                .mapNotNull { row -> row.dueLocal?.let { row.taskId to it } }.distinct().sortedBy { it.first },
            taskPersonLinks = dao.activeTaskPersonLinks(ids.scope, ids.personElchin)
                .map { it.taskId to it.domainPersonId }.sortedBy { it.first },
            calendarEvents = domain.allCalendarEvents(ids.scope).map { it.eventId to it.startLocal }.sortedBy { it.first },
            calendarPersonLinks = dao.activeCalendarPersonLinks(ids.scope, ids.personElchin)
                .map { it.eventId to it.domainPersonId }.sortedBy { it.first },
            conversationTurns = archive.recentTurns(ids.scope, 20).map { it.turnId }.sorted(),
            userUtterances = archive.recent(ids.scope, 20).filter { it.role == "user" }.map { it.message },
            episodes = archive.searchEpisodes(ids.scope, "BMW təmiri", limit = 10).map { it.episodeId }.sorted(),
            profileFacts = factRows.map { Triple(it.factId, it.category, it.fact) }.sortedBy { it.first },
            inboxSources = inboxRead.snapshot.captures.map { it.source.id to it.source.revisions.last().text }.sortedBy { it.first },
            legacyTasks = taskRead.tasks.map { it.id to it.dueLocal }.sortedBy { it.first },
            editableTableRows = tables.singleOrNull { it.table.tableId == ids.editableTable }
                ?.rows?.map { it.rowId to it.valuesJson }?.sortedBy { it.first } ?: emptyList(),
        )
    }

    private suspend fun seedOwnerConfirmedAlias(aliasId: String, personId: String, display: String) {
        val normalized = PersonAliasRepository.normalizeAlias(display)
        val row = PersonAliasRow(aliasId, ids.scope, personId, display, normalized,
            "${ids.scope.length}:${ids.scope}:$normalized", 1, "owner", null,
            fixedInstant.toEpochMilli(), fixedInstant.toEpochMilli(), null)
        dao.insertPersonAlias(row)
        dao.insertPersonAliasRevision(PersonAliasRevisionRow(aliasId, 1, ids.scope, personId,
            "added", display, normalized, "owner", null, fixedInstant.toEpochMilli()))
    }

    private suspend fun seedUserStatedFact(factId: String, category: String, fact: String,
        turnId: String, sourceText: String, quote: String) {
        val normalizedFact = Normalizer.normalize(fact, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val tokenized = Regex("[\\p{L}\\p{N}]+").findAll(normalizedFact).joinToString(" ") { it.value }
        val digestInput = "profile_fact_v1\u0000${ids.scope}\u0000$category\u0000$tokenized"
        val digest = MessageDigest.getInstance("SHA-256").digest(digestInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val now = fixedInstant.toEpochMilli()
        dao.insertProfileFact(ProfileFactRow(factId, ids.scope, category, fact, digest, 1, now, now))
        dao.insertProfileFactRevision(ProfileFactRevisionRow(ids.scope, factId, 1, category, fact,
            "user_stated", turnId, now))
        val start = sourceText.indexOf(quote)
        check(start >= 0) { "Synthetic profile source quote missing" }
        dao.insertProfileFactSource(ProfileFactSourceRow(ids.scope, factId, turnId, "user_stated",
            start, start + quote.length))
    }
}

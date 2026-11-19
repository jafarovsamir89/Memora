package ai.memora.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.DomainObjectRow
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.PersonRow
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskEvent
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.io.File
import kotlinx.coroutines.runBlocking

/** Idempotent persistent UI fixture. It accepts only the named lineage synthetic profile (Android user 10). */
internal object SyntheticFinanceUiJourneyProbe {
    private const val REQUIRED_ANDROID_USER = 10
    private const val PROFILE_NAME = "Synthetic Upgrade Sentinel"
    private const val LINEAGE_MARKER = "memora-local-install-state-v1"
    private const val PERSON_ID = "synthetic-p0-3-finance-person"
    private const val PERSON_NAME = "Synthetic P0.3 Receipt Person"
    private const val EVENT_ID = "00000000-0000-4000-8000-000000000271"
    private const val COMMAND_ID = "synthetic-p0-3-finance-command"
    private const val CASE_ID = "00000000-0000-4000-8000-000000000277"
    private const val DOMAIN_PERSON_ID = "00000000-0000-4000-8000-000000000276"
    private const val PARTICIPANT_ID = "00000000-0000-4000-8000-000000000278"
    private const val CASE_EVIDENCE_ID = "00000000-0000-4000-8000-000000000279"
    private const val IDENTITY_LINK_ID = "00000000-0000-4000-8000-000000000280"
    // AppRoute deliberately accepts only UUID-backed persisted source IDs.
    // Keep the first exploratory fixture turn intact and add this valid source turn.
    private const val TURN_ID = "00000000-0000-4000-8000-000000000273"
    private const val RESULT_ID = "00000000-0000-4000-8000-000000000274"
    private const val CASE_RESULT_ID = "00000000-0000-4000-8000-000000000281"
    private const val TASK_ID = "00000000-0000-4000-8000-000000000282"
    private const val TASK_EVIDENCE_ID = "00000000-0000-4000-8000-000000000283"
    private const val BMW_DEBT_ID = "00000000-0000-4000-8000-000000000285"
    private const val BMW_ADVANCE_ID = "00000000-0000-4000-8000-000000000286"
    private const val BMW_DEBT_EVIDENCE_ID = "00000000-0000-4000-8000-000000000288"
    private const val BMW_ADVANCE_EVIDENCE_ID = "00000000-0000-4000-8000-000000000289"
    private const val SETTLEMENT_ID = "00000000-0000-4000-8000-000000000293"
    private const val ADVANCE_TURN_ID = "00000000-0000-4000-8000-000000000290"
    private const val ADVANCE_RESULT_ID = "00000000-0000-4000-8000-000000000291"
    private const val TASK_RESULT_ID = "00000000-0000-4000-8000-000000000292"
    private const val AMBIGUOUS_TURN_ID = "00000000-0000-4000-8000-000000000294"
    private const val AMBIGUOUS_RESULT_ID = "00000000-0000-4000-8000-000000000295"
    private const val CORRECTION_EVENT_ID = "00000000-0000-4000-8000-000000000296"
    private const val CORRECTION_TURN_ID = "00000000-0000-4000-8000-000000000297"
    private const val CORRECTION_RESULT_ID = "00000000-0000-4000-8000-000000000298"
    private const val CORRECTION_EVENT_COMMAND_ID = "synthetic-p0-3-correction-event-command"
    private const val CORRECTION_COMMAND_ID = "synthetic-p0-3-correction-command"
    private const val AMBIGUOUS_USER_MESSAGE =
        "Sintetik yoxlama: Ruslan mənə 150 manat qaytardı; hansı Ruslan olduğunu dəqiqləşdirin."
    private const val AMBIGUOUS_MODEL_MESSAGE =
        "Hansı Ruslanı nəzərdə tutursunuz? Qeydi dəqiqləşdirmədən heç nə saxlamadım."
    private const val CORRECTION_USER_MESSAGE =
        "Sintetik yoxlama: nümunə qəbzin məbləği 50 AZN idi, sonra 45 AZN-ə düzəldildi."
    private const val CORRECTION_MODEL_MESSAGE =
        "Sintetik nəticə: məbləğ 45 AZN olaraq düzəldildi və tarixçədə saxlanıldı."
    private const val CORRECTION_SOURCE = "synthetic-correction-ui"
    private const val CORRECTION_REASON = "Synthetic correction UI readback"
    private const val CORRECTION_TIME = "2026-11-19T12:03:00Z"
    private const val TASK_COMMAND_ID = "synthetic-p0-3-task-create"
    private const val TASK_TITLE = "Synthetic confirm BMW repair appointment"
    private const val TIME = "2026-11-18T12:00:00Z"
    private const val CASE_TIME = "2026-11-18T14:00:00Z"
    private const val LINK_TIME = "2026-11-18T14:01:00Z"
    private const val TURN_TIME = 1_795_024_800_000L
    private const val USER_MESSAGE = "Synthetic UI fixture: open the saved 123.45 AZN receipt."
    private const val MODEL_MESSAGE = "Synthetic UI fixture: the exact receipt is linked below."
    private const val BMW_USER_MESSAGE = "Sintetik ssenari: Synthetic Elçin BMW təmiri üçün 100 AZN avans verdi; servis görüşünü təsdiqlə."
    private const val BMW_MODEL_MESSAGE = "Sintetik ssenari: 300 AZN razılaşdırılıb, 100 AZN avans alınıb və 200 AZN qalıb; görüş tapşırığı yaradılıb."
    private const val SOURCE = "synthetic-acceptance-ui"
    private const val COMMENT = "Synthetic persistent UI journey fixture; no live AI action."
    private const val BMW_FINANCE_SOURCE = "synthetic-bmw-acceptance-ui"
    private const val BMW_DEBT_COMMENT = "Synthetic BMW repair agreed total."
    private const val BMW_ADVANCE_COMMENT = "Synthetic BMW repair advance received."

    fun run(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            Process.myUid() / 100_000 != REQUIRED_ANDROID_USER || !isEmulator()) {
            result.putString("status", "named_synthetic_emulator_user_10_opt_in_required")
            return result
        }

        return runCatching {
            val marker = File(context.noBackupFilesDir, LINEAGE_MARKER)
            check(marker.isFile && marker.readText(Charsets.UTF_8) == "complete") {
                "synthetic_lineage_profile_required"
            }
            val database = AgentDatabase.open(context)
            val dao = database.protectedStoreDao()
            val profile = runBlocking { dao.localProfile() }
            check(profile?.displayName == PROFILE_NAME) { "unexpected_synthetic_profile" }
            val scope = AgentVoiceTurnProcessor(context, database).conversationScopeId()
            val existingLineagePerson = runBlocking { dao.person(scope, "synthetic-p0-person") }
            check(existingLineagePerson == PersonRow("synthetic-p0-person", "Synthetic Upgrade Person", scope)) {
                "synthetic_lineage_sentinel_missing"
            }
            val existingLineageEvent = runBlocking { dao.scopedMoneyEvent(scope, "synthetic-p0-money-event") }
            check(existingLineageEvent?.minorUnits == 12_345L) { "synthetic_lineage_finance_sentinel_missing" }

            val expectedPerson = PersonRow(PERSON_ID, PERSON_NAME, scope)
            val existingPerson = runBlocking { dao.person(scope, PERSON_ID) }
            check(existingPerson == null || existingPerson == expectedPerson) { "synthetic_journey_person_collision" }
            val expectedEvent = MoneyEventRow(
                eventId = EVENT_ID,
                commandId = COMMAND_ID,
                personId = PERSON_ID,
                kind = "receipt",
                minorUnits = 12_345L,
                currency = "AZN",
                source = SOURCE,
                recordedAt = TIME,
                occurredAt = TIME,
                comment = COMMENT,
                scopeId = scope,
            )
            val domain = ProtectedDomainRepository(database)
            val event = runBlocking {
                domain.recordFinanceEvent(
                    scope, PERSON_NAME, "receipt", 12_345L, "AZN", SOURCE, COMMENT,
                    PERSON_ID, EVENT_ID, COMMAND_ID, TIME, TIME,
                )
            }
            check(event == expectedEvent) { "synthetic_journey_finance_readback_mismatch" }
            check(runBlocking { dao.person(scope, PERSON_ID) } == expectedPerson) {
                "synthetic_journey_person_readback_mismatch"
            }

            val expectedDebt = MoneyEventRow(
                eventId = BMW_DEBT_ID,
                commandId = "synthetic-p0-3-bmw-debt-command",
                personId = PERSON_ID,
                kind = "debt",
                minorUnits = 30_000L,
                currency = "AZN",
                source = BMW_FINANCE_SOURCE,
                recordedAt = CASE_TIME,
                occurredAt = CASE_TIME,
                comment = BMW_DEBT_COMMENT,
                scopeId = scope,
            )
            val debt = runBlocking {
                domain.recordFinanceEvent(scope, PERSON_NAME, "debt", 30_000L, "AZN", BMW_FINANCE_SOURCE,
                    BMW_DEBT_COMMENT, PERSON_ID, BMW_DEBT_ID, expectedDebt.commandId, CASE_TIME, CASE_TIME)
            }
            check(debt == expectedDebt) { "synthetic_journey_bmw_obligation_readback_mismatch" }
            val expectedAdvance = MoneyEventRow(
                eventId = BMW_ADVANCE_ID,
                commandId = "synthetic-p0-3-bmw-advance-command",
                personId = PERSON_ID,
                kind = "receipt",
                minorUnits = 10_000L,
                currency = "AZN",
                source = BMW_FINANCE_SOURCE,
                recordedAt = LINK_TIME,
                occurredAt = LINK_TIME,
                comment = BMW_ADVANCE_COMMENT,
                scopeId = scope,
            )
            val advance = runBlocking {
                domain.recordFinanceEvent(scope, PERSON_NAME, "receipt", 10_000L, "AZN", BMW_FINANCE_SOURCE,
                    BMW_ADVANCE_COMMENT, PERSON_ID, BMW_ADVANCE_ID, expectedAdvance.commandId, LINK_TIME, LINK_TIME)
            }
            check(advance == expectedAdvance) { "synthetic_journey_bmw_advance_readback_mismatch" }
            val remainingDebt = runBlocking {
                domain.recordSettlement(scope, SETTLEMENT_ID, "synthetic-p0-3-bmw-settlement-command",
                    BMW_DEBT_ID, 10_000L, "AZN", LINK_TIME, "Synthetic advance received", BMW_ADVANCE_ID)
            }
            check(remainingDebt == 20_000L &&
                runBlocking { domain.appliedToDebtMinor(scope, BMW_ADVANCE_ID) } == 10_000L) {
                "synthetic_journey_bmw_advance_allocation_readback_mismatch"
            }

            val domainPerson = DomainObjectRow(
                objectId = DOMAIN_PERSON_ID,
                scopeId = scope,
                kind = DomainKind.PERSON.name,
                title = PERSON_NAME,
                body = "Synthetic identity for persistent case navigation.",
                status = "active",
                version = 1,
                deletedAt = null,
            )
            val existingDomainPerson = runBlocking { dao.domainObject(scope, DOMAIN_PERSON_ID) }
            check(existingDomainPerson == null || existingDomainPerson == domainPerson) {
                "synthetic_journey_domain_person_collision"
            }
            if (existingDomainPerson == null) {
                runBlocking {
                    domain.createObject(scope, DOMAIN_PERSON_ID, DomainKind.PERSON, PERSON_NAME,
                        domainPerson.body, null, CASE_TIME)
                }
            }
            check(runBlocking { dao.domainObject(scope, DOMAIN_PERSON_ID) } == domainPerson) {
                "synthetic_journey_domain_person_readback_mismatch"
            }

            val cases = ProtectedCaseRepository(database)
            val expectedCase = CaseRecordRow(
                caseId = CASE_ID,
                scopeId = scope,
                title = "Synthetic BMW repair",
                summary = "Synthetic case linking the exact receipt and person.",
                status = "open",
                nextAction = "Review the linked synthetic receipt",
                version = 1,
                sourceTurnId = TURN_ID,
                createdAt = CASE_TIME,
                updatedAt = CASE_TIME,
                deletedAt = null,
            )
            val case = runBlocking {
                cases.createCase(scope, CASE_ID, expectedCase.title, expectedCase.summary,
                    nextAction = expectedCase.nextAction, sourceTurnId = TURN_ID, now = CASE_TIME)
            }
            check(case == expectedCase) { "synthetic_journey_case_readback_mismatch" }
            val identity = runBlocking {
                cases.confirmFinancePersonIdentity(scope, PERSON_ID, DOMAIN_PERSON_ID,
                    linkId = IDENTITY_LINK_ID, confirmedBy = "owner", now = LINK_TIME)
            }
            check(identity.linkId == IDENTITY_LINK_ID && identity.financePersonId == PERSON_ID &&
                identity.domainPersonId == DOMAIN_PERSON_ID && identity.verifiedBy == "owner") {
                "synthetic_journey_identity_link_readback_mismatch"
            }
            val expectedTaskEvent = TaskEvent.Created(
                taskId = TASK_ID,
                scopeId = scope,
                actorId = "synthetic-p0-3-journey",
                commandId = TASK_COMMAND_ID,
                title = TASK_TITLE,
            )
            val domainRepository = ProtectedDomainRepository(database)
            val existingTaskEvents = runBlocking { dao.taskEvents(TASK_ID, scope) }
            check(existingTaskEvents.isEmpty() || existingTaskEvents.singleOrNull()?.let {
                it.scopeId == scope && it.actorId == expectedTaskEvent.actorId &&
                    it.commandId == TASK_COMMAND_ID && it.type == "created" && it.title == TASK_TITLE
            } == true) { "synthetic_journey_task_collision" }
            val task = if (existingTaskEvents.isEmpty()) {
                runBlocking { domainRepository.appendTaskEvent(expectedTaskEvent) }
            } else {
                runBlocking { ProtectedMemoryRepository(database).tasks().tasks.singleOrNull { it.id == TASK_ID } }
                    ?: error("synthetic_journey_task_projection_missing")
            }
            val expectedTask = Task(TASK_ID, scope, TASK_TITLE, completed = false, version = 1)
            check(task == expectedTask && runBlocking { dao.taskEvents(TASK_ID, scope).size } == 1) {
                "synthetic_journey_task_readback_mismatch"
            }
            runBlocking {
                cases.addParticipant(scope, CASE_ID, DOMAIN_PERSON_ID, role = "customer",
                    participantId = PARTICIPANT_ID, now = LINK_TIME)
                cases.addEvidence(scope, CASE_ID, "money_event", EVENT_ID, relation = "repair_receipt",
                    evidenceId = CASE_EVIDENCE_ID, now = LINK_TIME)
                cases.addEvidence(scope, CASE_ID, "money_event", BMW_DEBT_ID, relation = "agreed_total",
                    evidenceId = BMW_DEBT_EVIDENCE_ID, now = LINK_TIME)
                cases.addEvidence(scope, CASE_ID, "money_event", BMW_ADVANCE_ID, relation = "advance_received",
                    evidenceId = BMW_ADVANCE_EVIDENCE_ID, now = LINK_TIME)
                cases.addEvidence(scope, CASE_ID, "task", TASK_ID, relation = "confirm_repair_appointment",
                    evidenceId = TASK_EVIDENCE_ID, now = LINK_TIME)
            }
            val caseSnapshot = requireNotNull(runBlocking { cases.caseSnapshot(scope, CASE_ID) })
            check(caseSnapshot.participants.singleOrNull()?.objectId == DOMAIN_PERSON_ID &&
                caseSnapshot.evidence.size == 4 && caseSnapshot.evidence.all { it.sourceAvailable } &&
                caseSnapshot.evidence.map { it.evidence.evidenceId to it.evidence.sourceId }.toSet() == setOf(
                    CASE_EVIDENCE_ID to EVENT_ID,
                    BMW_DEBT_EVIDENCE_ID to BMW_DEBT_ID,
                    BMW_ADVANCE_EVIDENCE_ID to BMW_ADVANCE_ID,
                    TASK_EVIDENCE_ID to TASK_ID,
                )) { "synthetic_journey_case_relationship_readback_mismatch" }

            val expectedResult = AgentActionResultRow(
                resultId = RESULT_ID,
                scopeId = scope,
                turnId = TURN_ID,
                toolName = "record_money_event",
                status = "APPLIED",
                code = "receipt_recorded",
                targetKind = "finance",
                targetId = EVENT_ID,
                createdAtMillis = TURN_TIME,
            )
            val expectedCaseResult = AgentActionResultRow(
                resultId = CASE_RESULT_ID,
                scopeId = scope,
                turnId = TURN_ID,
                toolName = "create_case",
                status = "APPLIED",
                code = "case_created",
                targetKind = "case",
                targetId = CASE_ID,
                createdAtMillis = TURN_TIME,
            )
            val expectedAdvanceResult = AgentActionResultRow(
                resultId = ADVANCE_RESULT_ID,
                scopeId = scope,
                turnId = ADVANCE_TURN_ID,
                toolName = "record_money_event",
                status = "APPLIED",
                code = "receipt_recorded",
                targetKind = "finance",
                targetId = BMW_ADVANCE_ID,
                createdAtMillis = TURN_TIME + 1,
            )
            val expectedTaskResult = AgentActionResultRow(
                resultId = TASK_RESULT_ID,
                scopeId = scope,
                turnId = ADVANCE_TURN_ID,
                toolName = "create_task",
                status = "APPLIED",
                code = "task_created",
                targetKind = "task",
                targetId = TASK_ID,
                createdAtMillis = TURN_TIME + 1,
            )
            runBlocking {
                ProtectedConversationRepository(database).appendTurn(
                    scope, ADVANCE_TURN_ID, BMW_USER_MESSAGE, BMW_MODEL_MESSAGE, TURN_TIME + 1,
                    listOf(expectedAdvanceResult, expectedTaskResult),
                )
            }
            val conversation = ProtectedConversationRepository(database)
            val messages = runBlocking { dao.conversationMessagesForTurn(scope, TURN_ID) }
            check(messages.size == 2 && messages.map { it.role to it.message }.toSet() == setOf(
                "user" to USER_MESSAGE, "model" to MODEL_MESSAGE,
            )) { "synthetic_journey_conversation_readback_mismatch" }
            val actions = runBlocking { conversation.actionResults(scope, TURN_ID) }
            check(actions == listOf(expectedResult, expectedCaseResult)) {
                "synthetic_journey_action_card_readback_mismatch"
            }
            val bmwMessages = runBlocking { dao.conversationMessagesForTurn(scope, ADVANCE_TURN_ID) }
            check(bmwMessages.size == 2 && bmwMessages.map { it.role to it.message }.toSet() == setOf(
                "user" to BMW_USER_MESSAGE, "model" to BMW_MODEL_MESSAGE,
            )) { "synthetic_journey_bmw_conversation_readback_mismatch" }
            val bmwActions = runBlocking { conversation.actionResults(scope, ADVANCE_TURN_ID) }
            check(bmwActions == listOf(expectedAdvanceResult, expectedTaskResult)) {
                "synthetic_journey_bmw_action_readback_mismatch"
            }
            val sourceTurn = runBlocking {
                dao.actionResultsForTarget(scope, "finance", EVENT_ID, 20)
                    .firstOrNull { it.status == "APPLIED" }?.turnId
            }
            check(sourceTurn == TURN_ID) { "synthetic_journey_finance_source_not_exact" }
            val taskSourceTurn = runBlocking {
                dao.actionResultsForTarget(scope, "task", TASK_ID, 20)
                    .firstOrNull { it.status == "APPLIED" }?.turnId
            }
            check(taskSourceTurn == ADVANCE_TURN_ID) { "synthetic_journey_task_source_not_exact" }
            val advanceSourceTurn = runBlocking {
                dao.actionResultsForTarget(scope, "finance", BMW_ADVANCE_ID, 20)
                    .firstOrNull { it.status == "APPLIED" }?.turnId
            }
            check(advanceSourceTurn == ADVANCE_TURN_ID) { "synthetic_journey_advance_source_not_exact" }
            check(AppRoute.source(sourceTurn) != null) { "synthetic_journey_source_route_rejected" }
            check(AppRoute.source(advanceSourceTurn) != null) { "synthetic_journey_bmw_source_route_rejected" }

            // This separate receipt is a monotonic synthetic fixture. The production correction
            // repository appends one amount_correction row; the existing finance UI must read it
            // back by the same exact ID. No assistant/model/network request is involved here.
            val expectedCorrectionEvent = MoneyEventRow(
                eventId = CORRECTION_EVENT_ID,
                commandId = CORRECTION_EVENT_COMMAND_ID,
                personId = PERSON_ID,
                kind = "receipt",
                minorUnits = 5_000L,
                currency = "AZN",
                source = CORRECTION_SOURCE,
                recordedAt = CORRECTION_TIME,
                occurredAt = CORRECTION_TIME,
                comment = "Synthetic correction fixture",
                scopeId = scope,
            )
            val correctionBase = runBlocking {
                domain.recordFinanceEvent(scope, PERSON_NAME, "receipt", 5_000L, "AZN", CORRECTION_SOURCE,
                    "Synthetic correction fixture", PERSON_ID, CORRECTION_EVENT_ID,
                    CORRECTION_EVENT_COMMAND_ID, CORRECTION_TIME, CORRECTION_TIME)
            }
            check(correctionBase == expectedCorrectionEvent) { "synthetic_correction_base_readback_mismatch" }
            val correction = runBlocking {
                domain.correctFinanceEventAmount(scope, CORRECTION_EVENT_ID, 4_500L, CORRECTION_COMMAND_ID,
                    CORRECTION_REASON, CORRECTION_TIME)
            }
            check(correction.event.eventId == CORRECTION_EVENT_ID && correction.event.minorUnits == 4_500L) {
                "synthetic_correction_effective_amount_mismatch"
            }
            val correctionAdjustments = runBlocking { dao.adjustments(scope, CORRECTION_EVENT_ID) }
            check(correctionAdjustments.size == 1 && correctionAdjustments.single().let {
                it.commandId == CORRECTION_COMMAND_ID && it.kind == "amount_correction" &&
                    it.minorUnits == -500L && it.currency == "AZN" &&
                    it.reversesEventId == CORRECTION_EVENT_ID && it.reason == CORRECTION_REASON
            }) { "synthetic_correction_append_only_readback_mismatch" }
            val correctionResult = AgentActionResultRow(
                resultId = CORRECTION_RESULT_ID,
                scopeId = scope,
                turnId = CORRECTION_TURN_ID,
                toolName = "correct_money_event",
                status = "APPLIED",
                code = "money_event_corrected",
                targetKind = "finance",
                targetId = CORRECTION_EVENT_ID,
                createdAtMillis = java.time.Instant.parse(CORRECTION_TIME).toEpochMilli(),
            )
            val correctionConversation = ProtectedConversationRepository(database)
            runBlocking {
                correctionConversation.appendTurn(scope, CORRECTION_TURN_ID, CORRECTION_USER_MESSAGE,
                    CORRECTION_MODEL_MESSAGE, correctionResult.createdAtMillis, listOf(correctionResult))
            }
            val correctionMessages = runBlocking { dao.conversationMessagesForTurn(scope, CORRECTION_TURN_ID) }
            val correctionResults = runBlocking { correctionConversation.actionResults(scope, CORRECTION_TURN_ID) }
            check(correctionMessages.size == 2 && correctionMessages.map { it.role to it.message }.toSet() == setOf(
                "user" to CORRECTION_USER_MESSAGE, "model" to CORRECTION_MODEL_MESSAGE,
            )) { "synthetic_correction_conversation_readback_mismatch" }
            check(correctionResults == listOf(correctionResult)) { "synthetic_correction_action_card_readback_mismatch" }
            check(runBlocking { domain.moneyEvent(scope, CORRECTION_EVENT_ID)?.minorUnits } == 4_500L) {
                "synthetic_correction_final_readback_mismatch"
            }

            val ambiguousFinanceEventsBefore = runBlocking { dao.moneyEvents(scope).size }
            val expectedAmbiguousResult = AgentActionResultRow(
                resultId = AMBIGUOUS_RESULT_ID,
                scopeId = scope,
                turnId = AMBIGUOUS_TURN_ID,
                toolName = "record_money_event",
                status = "NEEDS_CONFIRMATION",
                code = "person_name_ambiguous",
                targetKind = null,
                targetId = null,
                createdAtMillis = TURN_TIME + 2,
            )
            val ambiguousConversation = ProtectedConversationRepository(database)
            val existingAmbiguousMessages = runBlocking {
                dao.conversationMessagesForTurn(scope, AMBIGUOUS_TURN_ID)
            }
            val existingAmbiguousResults = runBlocking {
                ambiguousConversation.actionResults(scope, AMBIGUOUS_TURN_ID)
            }
            if (existingAmbiguousMessages.isEmpty() && existingAmbiguousResults.isEmpty()) {
                runBlocking {
                    ambiguousConversation.appendTurn(scope, AMBIGUOUS_TURN_ID,
                        AMBIGUOUS_USER_MESSAGE, AMBIGUOUS_MODEL_MESSAGE, TURN_TIME + 2,
                        listOf(expectedAmbiguousResult))
                }
            }
            val ambiguousMessages = runBlocking {
                dao.conversationMessagesForTurn(scope, AMBIGUOUS_TURN_ID)
            }
            val ambiguousResults = runBlocking {
                ambiguousConversation.actionResults(scope, AMBIGUOUS_TURN_ID)
            }
            check(ambiguousMessages.size == 2 && ambiguousMessages.map { it.role to it.message }.toSet() == setOf(
                "user" to AMBIGUOUS_USER_MESSAGE, "model" to AMBIGUOUS_MODEL_MESSAGE,
            )) { "synthetic_journey_ambiguous_conversation_readback_mismatch" }
            check(ambiguousResults == listOf(expectedAmbiguousResult)) {
                "synthetic_journey_ambiguous_result_readback_mismatch"
            }
            val ambiguousFinanceEventsAfter = runBlocking { dao.moneyEvents(scope).size }
            check(ambiguousFinanceEventsAfter == ambiguousFinanceEventsBefore) {
                "synthetic_journey_ambiguity_created_finance_event"
            }

            result.putString("status", "synthetic_finance_ui_journey_fixture_passed")
            result.putString("finance_event_id", EVENT_ID)
            result.putString("conversation_turn_id", TURN_ID)
            result.putString("source_turn_id", sourceTurn)
            result.putString("advance_turn_id", advanceSourceTurn)
            result.putString("case_id", CASE_ID)
            result.putString("person_id", DOMAIN_PERSON_ID)
            result.putString("task_id", TASK_ID)
            result.putString("obligation_event_id", BMW_DEBT_ID)
            result.putString("advance_event_id", BMW_ADVANCE_ID)
            result.putLong("minor_units", event.minorUnits)
            result.putLong("obligation_minor_units", debt.minorUnits)
            result.putLong("advance_minor_units", advance.minorUnits)
            result.putLong("outstanding_minor_units", remainingDebt)
            result.putLong("advance_applied_minor_units", runBlocking { domain.appliedToDebtMinor(scope, BMW_ADVANCE_ID) })
            result.putInt("finance_event_count", runBlocking { dao.moneyEvents(scope).count { it.eventId == EVENT_ID } })
            result.putInt("conversation_message_count", messages.size)
            result.putInt("action_result_count", actions.size)
            result.putInt("bmw_conversation_message_count", bmwMessages.size)
            result.putInt("bmw_action_result_count", bmwActions.size)
            result.putInt("task_event_count", runBlocking { dao.taskEvents(TASK_ID, scope).size })
            result.putInt("case_evidence_count", caseSnapshot.evidence.size)
            result.putString("ambiguous_turn_id", AMBIGUOUS_TURN_ID)
            result.putString("ambiguous_result_id", AMBIGUOUS_RESULT_ID)
            result.putInt("ambiguous_message_count", ambiguousMessages.size)
            result.putInt("ambiguous_action_result_count", ambiguousResults.size)
            result.putInt("ambiguous_finance_events_before", ambiguousFinanceEventsBefore)
            result.putInt("ambiguous_finance_events_after", ambiguousFinanceEventsAfter)
            result.putString("ambiguous_status", ambiguousResults.single().status)
            result.putString("ambiguous_code", ambiguousResults.single().code)
            result.putString("ambiguous_target_present", (ambiguousResults.single().targetId != null).toString())
            result.putString("correction_event_id", CORRECTION_EVENT_ID)
            result.putString("correction_turn_id", CORRECTION_TURN_ID)
            result.putInt("correction_effective_minor_units", correction.event.minorUnits.toInt())
            result.putInt("correction_adjustment_minor_units", correctionAdjustments.single().minorUnits.toInt())
            result.putInt("correction_adjustment_count", correctionAdjustments.size)
            result.putInt("correction_message_count", correctionMessages.size)
            result.putInt("correction_action_result_count", correctionResults.size)
            result.putString("correction_target_matches", (correctionResults.single().targetId == CORRECTION_EVENT_ID).toString())
            result
        }.getOrElse { error ->
            result.putString("status", "synthetic_finance_ui_journey_fixture_failed")
            result.putString("stage_error", error.javaClass.simpleName.take(64))
            result
        }
    }

    private fun isEmulator(): Boolean = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) || Build.HARDWARE.contains("ranchu", ignoreCase = true)
}

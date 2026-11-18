package ai.memora.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.PersonRow
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
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
    // AppRoute deliberately accepts only UUID-backed persisted source IDs.
    // Keep the first exploratory fixture turn intact and add this valid source turn.
    private const val TURN_ID = "00000000-0000-4000-8000-000000000273"
    private const val RESULT_ID = "00000000-0000-4000-8000-000000000274"
    private const val TIME = "2026-11-18T12:00:00Z"
    private const val TURN_TIME = 1_795_024_800_000L
    private const val USER_MESSAGE = "Synthetic UI fixture: open the saved 123.45 AZN receipt."
    private const val MODEL_MESSAGE = "Synthetic UI fixture: the exact receipt is linked below."
    private const val SOURCE = "synthetic-acceptance-ui"
    private const val COMMENT = "Synthetic persistent UI journey fixture; no live AI action."

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
            runBlocking {
                ProtectedConversationRepository(database).appendTurn(
                    scope, TURN_ID, USER_MESSAGE, MODEL_MESSAGE, TURN_TIME, listOf(expectedResult),
                )
            }
            val conversation = ProtectedConversationRepository(database)
            val messages = runBlocking { dao.conversationMessagesForTurn(scope, TURN_ID) }
            check(messages.size == 2 && messages.map { it.role to it.message }.toSet() == setOf(
                "user" to USER_MESSAGE, "model" to MODEL_MESSAGE,
            )) { "synthetic_journey_conversation_readback_mismatch" }
            val actions = runBlocking { conversation.actionResults(scope, TURN_ID) }
            check(actions == listOf(expectedResult)) { "synthetic_journey_action_card_readback_mismatch" }
            val sourceTurn = runBlocking {
                dao.actionResultsForTarget(scope, "finance", EVENT_ID, 20)
                    .firstOrNull { it.status == "APPLIED" }?.turnId
            }
            check(sourceTurn == TURN_ID) { "synthetic_journey_finance_source_not_exact" }
            check(AppRoute.source(sourceTurn) != null) { "synthetic_journey_source_route_rejected" }

            result.putString("status", "synthetic_finance_ui_journey_fixture_passed")
            result.putString("finance_event_id", EVENT_ID)
            result.putString("conversation_turn_id", TURN_ID)
            result.putString("source_turn_id", sourceTurn)
            result.putLong("minor_units", event.minorUnits)
            result.putInt("finance_event_count", runBlocking { dao.moneyEvents(scope).count { it.eventId == EVENT_ID } })
            result.putInt("conversation_message_count", messages.size)
            result.putInt("action_result_count", actions.size)
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

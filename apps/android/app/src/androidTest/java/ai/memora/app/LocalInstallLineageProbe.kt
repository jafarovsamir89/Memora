package ai.memora.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import ai.memora.app.data.LocalProfileInput
import ai.memora.app.data.ProtectedLocalProfileStore
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.DatabaseKeyProvider
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.PersonRow
import ai.memora.app.data.protectedstore.ProfileFactRow
import ai.memora.app.data.protectedstore.TaskEventRow
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.reminders.AttentionNotifications
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.runBlocking
import java.io.File

/** Explicitly opted-in synthetic fixture for verifying APK update preservation on a disposable emulator. */
internal object LocalInstallLineageProbe {
    private const val PROFILE_FACT_ID = "synthetic-p0-profile-fact"
    private const val CASE_ID = "00000000-0000-4000-8000-000000000091"
    private const val ACTION_RESULT_ID = "00000000-0000-4000-8000-000000000092"
    private const val ACTION_TURN_ID = "synthetic-stage-one-action-card"
    private const val PERSON_ID = "synthetic-p0-person"
    private const val MONEY_EVENT_ID = "synthetic-p0-money-event"
    private const val TASK_ID = "synthetic-p0-task"
    private const val MARKER = "memora-local-install-state-v1"

    fun run(context: Context, phase: String, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || phase !in setOf("prepare-notification-race", "seed", "verify", "verify-new-install") ||
            context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !(Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true))) {
            result.putString("status", "synthetic_emulator_opt_in_required")
            return result
        }

        return runCatching {
            val marker = File(context.noBackupFilesDir, MARKER)
            if (phase == "prepare-notification-race") {
                check(!marker.exists()) { "install_marker_already_present" }
                val databaseFile = context.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME)
                AttentionNotifications.reconcileFromApp(context)
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                while (!databaseFile.isFile && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25L)
                check(databaseFile.isFile) { "notification_reconciliation_did_not_create_database" }
                check(!marker.exists()) { "notification_reconciliation_created_install_marker" }
                result.putString("status", "synthetic_lineage_notification_prepared")
                result.putBoolean("database_created_before_profile_ui", true)
                result.putBoolean("install_marker_still_absent", true)
                return@runCatching result
            }
            if (phase == "verify-new-install") {
                check(marker.isFile && marker.readText(Charsets.UTF_8) == "new") {
                    "fresh_install_marker_not_new"
                }
                val database = AgentDatabase.open(context)
                val emptyProfile = runBlocking { database.protectedStoreDao().localProfile() == null }
                check(emptyProfile) { "fresh_install_profile_not_empty" }
                result.putString("status", "synthetic_lineage_new_install_passed")
                result.putBoolean("database_created_before_ui_without_legacy_state", true)
                result.putBoolean("onboarding_marker_new", true)
                result.putBoolean("profile_empty", true)
                return@runCatching result
            }
            val database = AgentDatabase.open(context)
            val dao = database.protectedStoreDao()
            val scope = AgentVoiceTurnProcessor(context, database).conversationScopeId()
            val conversations = ProtectedConversationRepository(database)
            if (phase == "seed") {
                check(!marker.exists()) { "install_marker_already_present" }
                runBlocking {
                    val nowMillis = System.currentTimeMillis()
                    check(dao.localProfile() == null) { "profile_not_empty_before_seed" }
                    check(dao.profileFacts(scope, 10).isEmpty()) { "profile_facts_not_empty_before_seed" }
                    check(dao.activeCases(scope, 10).isEmpty()) { "cases_not_empty_before_seed" }
                    check(dao.people(scope).isEmpty()) { "people_not_empty_before_seed" }
                    check(dao.moneyEvents(scope).isEmpty()) { "finance_not_empty_before_seed" }
                    check(dao.taskEvents(TASK_ID, scope).isEmpty()) { "tasks_not_empty_before_seed" }
                    check(conversations.actionResults(scope, ACTION_TURN_ID).isEmpty()) { "action_cards_not_empty_before_seed" }

                    ProtectedLocalProfileStore(database).complete(
                        LocalProfileInput("Synthetic Upgrade Sentinel", "az", "friendly", "balanced", "Asia/Baku"),
                    )
                    dao.insertProfileFact(ProfileFactRow(
                        PROFILE_FACT_ID, scope, "context", "Synthetic upgrade sentinel",
                        "synthetic-p0-digest", 1, 1L, 1L,
                    ))
                    dao.insertPerson(PersonRow(PERSON_ID, "Synthetic Upgrade Person", scope))
                    dao.insertCase(CaseRecordRow(
                        CASE_ID, scope, "Synthetic upgrade case", "Synthetic case sentinel", "open",
                        "Verify update", 1, "synthetic-p0-source-turn", "2026-11-15T00:00:00Z",
                        "2026-11-15T00:00:00Z", null,
                    ))
                    dao.insertMoneyEvent(MoneyEventRow(
                        MONEY_EVENT_ID, "synthetic-p0-command", PERSON_ID, "income", 12345L,
                        "AZN", "synthetic", "2026-11-15T00:00:00Z", null,
                        "synthetic update sentinel", scope,
                    ))
                    dao.putTaskEvents(listOf(TaskEventRow(
                        taskId = TASK_ID, scopeId = scope, actorId = "synthetic-p0-actor",
                        commandId = "synthetic-p0-task-command", type = "created", previousVersion = null,
                        title = "Synthetic upgrade task", completed = false, dueLocal = null,
                        dueZone = "Asia/Baku", remind = false, personLabel = null, projectLabel = null,
                    )))
                    conversations.appendTurn(
                        scopeId = scope,
                        turnId = ACTION_TURN_ID,
                        userMessage = "Synthetic action-card link sentinel",
                        assistantMessage = "Synthetic case link is ready.",
                        createdAtMillis = nowMillis,
                        actionResults = listOf(AgentActionResultRow(
                            resultId = ACTION_RESULT_ID,
                            scopeId = scope,
                            turnId = ACTION_TURN_ID,
                            toolName = "create_case",
                            status = "APPLIED",
                            code = "saved",
                            targetKind = "case",
                            targetId = CASE_ID,
                            createdAtMillis = nowMillis,
                        )),
                    )
                    assertSentinels(dao, conversations, scope)
                }
                check(!marker.exists()) { "seed_must_not_create_install_marker" }
                result.putString("status", "synthetic_lineage_seed_passed")
                result.putBoolean("marker_absent_before_update", true)
            } else {
                runBlocking { assertSentinels(dao, conversations, scope) }
                check(marker.isFile && marker.readText(Charsets.UTF_8) == "complete") {
                    "existing_profile_was_not_recognized_after_update"
                }
                result.putString("status", "synthetic_lineage_verify_passed")
                result.putBoolean("markerless_existing_profile_preserved", true)
            }
            result.putInt("profile_facts", 1)
            result.putInt("cases", 1)
            result.putInt("people", 1)
            result.putInt("money_events", 1)
            result.putInt("task_events", 1)
            result.putInt("action_cards", 1)
            result
        }.getOrElse { error ->
            result.putString("status", "synthetic_lineage_failed")
            result.putString("stage_error", error.javaClass.simpleName.take(64))
            result
        }
    }

    private suspend fun assertSentinels(
        dao: ai.memora.app.data.protectedstore.ProtectedStoreDao,
        conversations: ProtectedConversationRepository,
        scope: String,
    ) {
        check(dao.localProfile()?.displayName == "Synthetic Upgrade Sentinel") { "profile_sentinel_missing" }
        check(dao.profileFact(scope, PROFILE_FACT_ID)?.fact == "Synthetic upgrade sentinel") { "profile_fact_missing" }
        check(dao.caseRecord(scope, CASE_ID)?.title == "Synthetic upgrade case") { "case_sentinel_missing" }
        check(dao.person(scope, PERSON_ID)?.displayName == "Synthetic Upgrade Person") { "person_sentinel_missing" }
        check(dao.scopedMoneyEvent(scope, MONEY_EVENT_ID)?.minorUnits == 12345L) { "money_sentinel_missing" }
        check(dao.taskEvents(TASK_ID, scope).singleOrNull()?.title == "Synthetic upgrade task") { "task_sentinel_missing" }
        val action = conversations.actionResults(scope, ACTION_TURN_ID).singleOrNull()
        check(action?.status == "APPLIED" && action.targetKind == "case" && action.targetId == CASE_ID) {
            "synthetic_action_target_missing_or_changed"
        }
        check(conversations.turn(scope, ACTION_TURN_ID).any { it.role == "model" }) { "synthetic_action_turn_missing" }
    }
}

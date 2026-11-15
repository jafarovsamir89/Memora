package ai.memora.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import ai.memora.app.data.LocalProfileInput
import ai.memora.app.data.ProtectedLocalProfileStore
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.CaseRecordRow
import ai.memora.app.data.protectedstore.DatabaseKeyProvider
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.PersonRow
import ai.memora.app.data.protectedstore.ProfileFactRow
import ai.memora.app.data.protectedstore.TaskEventRow
import ai.memora.app.reminders.AttentionNotifications
import kotlinx.coroutines.runBlocking
import java.io.File

/** Explicitly opted-in synthetic fixture for verifying APK update preservation on a disposable emulator. */
internal object LocalInstallLineageProbe {
    private const val SCOPE = "synthetic-p0-install-lineage"
    private const val PROFILE_FACT_ID = "synthetic-p0-profile-fact"
    private const val CASE_ID = "synthetic-p0-case"
    private const val PERSON_ID = "synthetic-p0-person"
    private const val MONEY_EVENT_ID = "synthetic-p0-money-event"
    private const val TASK_ID = "synthetic-p0-task"
    private const val MARKER = "memora-local-install-state-v1"

    fun run(context: Context, phase: String, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || phase !in setOf("prepare-notification-race", "seed", "verify") ||
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
            val database = AgentDatabase.open(context)
            val dao = database.protectedStoreDao()
            if (phase == "seed") {
                check(!marker.exists()) { "install_marker_already_present" }
                runBlocking {
                    check(dao.localProfile() == null) { "profile_not_empty_before_seed" }
                    check(dao.profileFacts(SCOPE, 10).isEmpty()) { "profile_facts_not_empty_before_seed" }
                    check(dao.activeCases(SCOPE, 10).isEmpty()) { "cases_not_empty_before_seed" }
                    check(dao.people(SCOPE).isEmpty()) { "people_not_empty_before_seed" }
                    check(dao.moneyEvents(SCOPE).isEmpty()) { "finance_not_empty_before_seed" }
                    check(dao.taskEvents(TASK_ID, SCOPE).isEmpty()) { "tasks_not_empty_before_seed" }

                    ProtectedLocalProfileStore(database).complete(
                        LocalProfileInput("Synthetic Upgrade Sentinel", "az", "friendly", "balanced", "Asia/Baku"),
                    )
                    dao.insertProfileFact(ProfileFactRow(
                        PROFILE_FACT_ID, SCOPE, "context", "Synthetic upgrade sentinel",
                        "synthetic-p0-digest", 1, 1L, 1L,
                    ))
                    dao.insertPerson(PersonRow(PERSON_ID, "Synthetic Upgrade Person", SCOPE))
                    dao.insertCase(CaseRecordRow(
                        CASE_ID, SCOPE, "Synthetic upgrade case", "Synthetic case sentinel", "open",
                        "Verify update", 1, "synthetic-p0-source-turn", "2026-11-15T00:00:00Z",
                        "2026-11-15T00:00:00Z", null,
                    ))
                    dao.insertMoneyEvent(MoneyEventRow(
                        MONEY_EVENT_ID, "synthetic-p0-command", PERSON_ID, "income", 12345L,
                        "AZN", "synthetic", "2026-11-15T00:00:00Z", null,
                        "synthetic update sentinel", SCOPE,
                    ))
                    dao.putTaskEvents(listOf(TaskEventRow(
                        taskId = TASK_ID, scopeId = SCOPE, actorId = "synthetic-p0-actor",
                        commandId = "synthetic-p0-task-command", type = "created", previousVersion = null,
                        title = "Synthetic upgrade task", completed = false, dueLocal = null,
                        dueZone = "Asia/Baku", remind = false, personLabel = null, projectLabel = null,
                    )))
                    assertSentinels(dao)
                }
                check(!marker.exists()) { "seed_must_not_create_install_marker" }
                result.putString("status", "synthetic_lineage_seed_passed")
                result.putBoolean("marker_absent_before_update", true)
            } else {
                runBlocking { assertSentinels(dao) }
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
            result
        }.getOrElse { error ->
            result.putString("status", "synthetic_lineage_failed")
            result.putString("stage_error", error.javaClass.simpleName.take(64))
            result
        }
    }

    private suspend fun assertSentinels(dao: ai.memora.app.data.protectedstore.ProtectedStoreDao) {
        check(dao.localProfile()?.displayName == "Synthetic Upgrade Sentinel") { "profile_sentinel_missing" }
        check(dao.profileFact(SCOPE, PROFILE_FACT_ID)?.fact == "Synthetic upgrade sentinel") { "profile_fact_missing" }
        check(dao.caseRecord(SCOPE, CASE_ID)?.title == "Synthetic upgrade case") { "case_sentinel_missing" }
        check(dao.person(SCOPE, PERSON_ID)?.displayName == "Synthetic Upgrade Person") { "person_sentinel_missing" }
        check(dao.scopedMoneyEvent(SCOPE, MONEY_EVENT_ID)?.minorUnits == 12345L) { "money_sentinel_missing" }
        check(dao.taskEvents(TASK_ID, SCOPE).singleOrNull()?.title == "Synthetic upgrade task") { "task_sentinel_missing" }
    }
}

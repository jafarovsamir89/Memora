package ai.memora.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.room.withTransaction
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.MoneyEventRow
import ai.memora.app.data.protectedstore.PersonRow
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.runBlocking

/** Adds bounded cross-domain fixtures to one named synthetic emulator profile and measures Room reads. */
internal object SyntheticVolumeProbe {
    private const val MARKER = "synthetic-volume-finance-"
    private const val CLEAN_LAB_PROFILE = "SyntheticMemoraLab"
    private const val VOLUME_PERSON_ID = "synthetic-volume-person"
    private const val VOLUME_PERSON_NAME = "Synthetic Volume Person"
    private val allowedCounts = setOf(100, 1_000)

    fun run(context: Context, permitted: Boolean, targetCount: Int): Bundle {
        val result = Bundle()
        if (!permitted || targetCount !in allowedCounts || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            Process.myUid() / 100_000 == 0 || !isEmulator()) {
            result.putString("status", "synthetic_volume_emulator_opt_in_required")
            return result
        }

        var stage = "open_database"
        return runCatching {
            val database = AgentDatabase.open(context)
            stage = "validate_profile"
            val dao = database.protectedStoreDao()
            val userId = Process.myUid() / 100_000
            check(userId == 11) { "named_synthetic_volume_user_required" }
            val profile = runBlocking { dao.localProfile() }
            check(profile?.displayName == CLEAN_LAB_PROFILE) { "named_synthetic_profile_required" }
            stage = "resolve_scope_person"
            val scope = AgentVoiceTurnProcessor(context, database).conversationScopeId()
            val people = runBlocking { dao.people(scope) }
            check(people.all { it.personId == VOLUME_PERSON_ID && it.displayName == VOLUME_PERSON_NAME }) {
                "unexpected_people_in_volume_profile"
            }
            val person = people.singleOrNull() ?: PersonRow(VOLUME_PERSON_ID, VOLUME_PERSON_NAME, scope).also {
                runBlocking { dao.insertPerson(it) }
            }
            val repository = ProtectedDomainRepository(database)

            stage = "read_existing_fixture"
            val allEventsBefore = runBlocking { dao.moneyEvents(scope) }
            check(allEventsBefore.all { it.eventId.startsWith(MARKER) }) {
                "unexpected_finance_rows_in_volume_profile"
            }
            val rowsBefore = allEventsBefore.filter { it.eventId.startsWith(MARKER) }
            check(rowsBefore.size <= targetCount) { "volume_fixture_cannot_shrink" }
            rowsBefore.forEachIndexed { index, row ->
                check(row == rowAt(index, scope, person.personId)) { "volume_fixture_not_contiguous_or_changed" }
            }

            var inserted = 0
            var insertMillis = 0L
            if (rowsBefore.size < targetCount) {
                val rows = (rowsBefore.size until targetCount).map { rowAt(it, scope, person.personId) }
                val started = SystemClock.elapsedRealtimeNanos()
                stage = "insert_fixture"
                runBlocking { database.withTransaction { dao.putMoneyEvents(rows) } }
                insertMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                inserted = rows.size
            }

            stage = "verify_fixture"
            val fixture = runBlocking { dao.moneyEvents(scope).filter { it.eventId.startsWith(MARKER) } }
            check(fixture.size == targetCount) { "volume_fixture_count_mismatch" }
            fixture.forEachIndexed { index, row ->
                check(row == rowAt(index, scope, person.personId)) { "volume_fixture_readback_mismatch" }
            }

            // Warm once, then record twenty bounded local samples. These are device-local Room/repository
            // timings only; no network, live model, UI-frame, or peak-memory claim is made here.
            stage = "warmup_reads"
            runBlocking { repository.allFinanceEvents(scope) }
            stage = "dao_event_samples"
            val eventReadSamples = samples { runBlocking { dao.moneyEvents(scope) } }
            stage = "effective_event_samples"
            val effectiveReadSamples = samples { runBlocking { repository.allFinanceEvents(scope) } }
            stage = "finance_presentation_samples"
            val presentationSamples = samples { runBlocking { repository.financePresentationSnapshot(scope) } }
            stage = "finance_analytics_samples"
            val analyticsSamples = samples {
                runBlocking { repository.financeAnalytics(scope, YearMonth.of(2026, 11)) }
            }

            stage = "synthetic_domain_volume_probe"
            val domainMetrics = SyntheticDomainVolumeProbe.run(database, scope, targetCount)

            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            stage = "report_results"
            result.putString("status", "synthetic_volume_probe_passed")
            result.putString("profile", "named_synthetic_emulator_profile")
            result.putInt("synthetic_finance_rows", fixture.size)
            result.putInt("all_scope_finance_rows", runBlocking { dao.moneyEvents(scope).size })
            result.putInt("rows_inserted_this_run", inserted)
            result.putLong("seed_insert_ms", insertMillis)
            putSamples(result, "dao_event_read", eventReadSamples)
            putSamples(result, "effective_event_read", effectiveReadSamples)
            putSamples(result, "finance_presentation", presentationSamples)
            putSamples(result, "finance_analytics", analyticsSamples)
            result.putInt("process_pss_kib", memory.totalPss)
            result.putLong("java_heap_used_bytes", Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
            result.putString("fixture_digest_sha256", digest(fixture))
            result.putString("measurement_scope", "warm_local_reads_20_samples_per_path_no_ui_network")
            result
                .apply { putAll(domainMetrics) }
        }.getOrElse { error ->
            result.putString("status", "synthetic_volume_probe_failed")
            result.putString("stage", stage)
            result.putString("stage_error", error.javaClass.simpleName.take(64))
            result
        }
    }

    private fun rowAt(index: Int, scope: String, personId: String): MoneyEventRow {
        val suffix = index.toString().padStart(4, '0')
        val recordedAt = Instant.parse("2026-11-16T00:00:00Z").plusSeconds(index.toLong()).toString()
        return MoneyEventRow(
            eventId = "$MARKER$suffix",
            commandId = "$MARKER-command-$suffix",
            personId = personId,
            kind = "expense",
            minorUnits = 100L,
            currency = "AZN",
            source = "synthetic-volume",
            recordedAt = recordedAt,
            occurredAt = recordedAt,
            comment = "Synthetic volume finance row $suffix",
            scopeId = scope,
        )
    }

    private fun samples(read: suspend () -> Any): LongArray = runBlocking {
        LongArray(20) {
            val started = SystemClock.elapsedRealtimeNanos()
            read()
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        }
    }

    private fun putSamples(result: Bundle, name: String, values: LongArray) {
        val sorted = values.sorted()
        result.putLongArray("${name}_samples_ms", values)
        result.putLong("${name}_p50_ms", sorted[sorted.size / 2])
        result.putLong("${name}_p95_ms", sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)])
        result.putInt("${name}_sample_count", values.size)
    }

    private fun digest(rows: List<MoneyEventRow>): String {
        val canonical = rows.joinToString("\n") { "${it.eventId}|${it.personId}|${it.kind}|${it.minorUnits}|${it.currency}|${it.recordedAt}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun isEmulator(): Boolean = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) || Build.HARDWARE.contains("ranchu", ignoreCase = true)
}

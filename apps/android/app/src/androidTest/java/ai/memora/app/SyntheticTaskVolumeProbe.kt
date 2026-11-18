package ai.memora.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.StoreRead
import ai.memora.app.data.StoreWrite
import ai.memora.app.data.TaskStoreKey
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskEvent
import ai.memora.app.domain.TaskRules
import java.io.File
import java.security.MessageDigest

/** Synthetic-only volume fixture and measurements for the existing encrypted Tasks screen store. */
internal object SyntheticTaskVolumeProbe {
    private const val TASK_MARKER = "synthetic-volume-task-"
    private const val ACTOR_ID = "synthetic-volume-lab"
    private val allowedCounts = setOf(100, 1_000)

    fun run(context: Context, scope: String, targetCount: Int): Bundle {
        require(targetCount in allowedCounts) { "unsupported_synthetic_task_volume" }
        val file = File(context.filesDir, "tasks-prototype.bin")
        val store = EncryptedTaskStore(file, TaskStoreKey::get)
        val before = store.migrationSnapshot()
        check(before.deletedIds.isEmpty()) { "unexpected_deleted_task_markers_in_volume_profile" }
        check(before.events.size <= targetCount) { "task_fixture_cannot_shrink" }
        before.events.forEachIndexed { index, event ->
            check(event == eventAt(index, scope)) { "task_fixture_not_contiguous_or_changed" }
        }

        val started = SystemClock.elapsedRealtimeNanos()
        for (index in before.events.size until targetCount) {
            val event = eventAt(index, scope)
            val result = store.append(event)
            check(result == StoreWrite.Recorded) { "task_fixture_write_failed" }
        }
        val seedMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L

        val after = store.migrationSnapshot()
        check(after.events == (0 until targetCount).map { eventAt(it, scope) }) {
            "task_event_readback_mismatch"
        }
        val expectedTasks = (0 until targetCount).map { taskAt(it, scope) }
        val loadedTasks = (store.load() as? StoreRead.Ready)?.tasks ?: error("task_store_unavailable")
        check(loadedTasks == expectedTasks) { "task_projection_readback_mismatch" }

        val loadSamples = samples {
            val loaded = (store.load() as? StoreRead.Ready)?.tasks ?: error("task_store_sample_unavailable")
            check(loaded.size == targetCount) { "task_store_sample_count_mismatch" }
        }

        return Bundle().apply {
            putInt("synthetic_task_count", loadedTasks.size)
            putInt("task_rows_inserted_this_run", targetCount - before.events.size)
            putLong("task_store_file_bytes", file.length())
            putLong("task_store_seed_ms", seedMillis)
            putString("task_fixture_digest_sha256", digest(loadedTasks))
            putString("task_measurement_scope", "warm_local_encrypted_file_load_20_samples_no_ui_network")
            putSamples(this, "task_store_load", loadSamples)
        }
    }

    private fun eventAt(index: Int, scope: String): TaskEvent.Created {
        val suffix = index.toString().padStart(4, '0')
        val id = "$TASK_MARKER$suffix"
        return (TaskRules.create(
            id = id,
            scopeId = scope,
            actorId = ACTOR_ID,
            commandId = "$id-create",
            title = "Synthetic volume task $suffix",
        ) as? ai.memora.app.domain.TaskDecision.Changed)?.event as? TaskEvent.Created
            ?: error("invalid_synthetic_task_fixture")
    }

    private fun taskAt(index: Int, scope: String): Task {
        val event = eventAt(index, scope)
        return Task(event.taskId, event.scopeId, event.title, completed = false, version = 1)
    }

    private fun samples(read: () -> Unit): LongArray {
        read()
        return LongArray(20) {
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

    private fun digest(tasks: List<Task>): String {
        val canonical = tasks.joinToString("\n") { "${it.id}|${it.scopeId}|${it.title}|${it.version}|${it.completed}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

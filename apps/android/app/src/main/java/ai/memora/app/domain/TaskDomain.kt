package ai.memora.app.domain

import java.time.LocalDateTime
import java.time.ZoneId

/** Pure task decisions. Persistence, access checks and command deduplication belong to the caller. */
data class Task(
    val id: String,
    val scopeId: String,
    val title: String,
    val completed: Boolean,
    val version: Long,
    val dueLocal: String? = null,
    val dueZone: String? = null,
    val remind: Boolean = false,
    val personLabel: String? = null,
    val projectLabel: String? = null,
)

sealed interface TaskEvent {
    val taskId: String
    val scopeId: String
    val actorId: String
    val commandId: String

    data class Created(
        override val taskId: String,
        override val scopeId: String,
        override val actorId: String,
        override val commandId: String,
        val title: String,
    ) : TaskEvent

    data class Renamed(
        override val taskId: String,
        override val scopeId: String,
        override val actorId: String,
        override val commandId: String,
        val previousVersion: Long,
        val title: String,
    ) : TaskEvent

    data class CompletionChanged(
        override val taskId: String,
        override val scopeId: String,
        override val actorId: String,
        override val commandId: String,
        val previousVersion: Long,
        val completed: Boolean,
    ) : TaskEvent

    data class DetailsChanged(
        override val taskId: String,
        override val scopeId: String,
        override val actorId: String,
        override val commandId: String,
        val previousVersion: Long,
        val dueLocal: String?,
        val dueZone: String?,
        val remind: Boolean,
        val personLabel: String?,
        val projectLabel: String?,
    ) : TaskEvent

    data class Updated(
        override val taskId: String,
        override val scopeId: String,
        override val actorId: String,
        override val commandId: String,
        val previousVersion: Long,
        val title: String,
        val dueLocal: String?,
        val dueZone: String?,
        val remind: Boolean,
        val personLabel: String?,
        val projectLabel: String?,
    ) : TaskEvent
}

sealed interface TaskDecision {
    data class Changed(val event: TaskEvent) : TaskDecision
    data object Unchanged : TaskDecision
    data object Invalid : TaskDecision
    data object Conflict : TaskDecision
}

sealed interface TaskApply {
    data class Applied(val task: Task) : TaskApply
    data object Conflict : TaskApply
}

object TaskRules {
    private fun validIds(vararg ids: String): Boolean = ids.all { it.isNotBlank() }
    private fun validTitle(title: String): Boolean =
        title.isNotBlank() && title == title.trim() && title.codePointCount(0, title.length) <= 200
    private fun validDetails(local: String?, zone: String?, remind: Boolean, person: String?, project: String?): Boolean {
        if (listOf(person, project).any { it != null && (it.isBlank() || it != it.trim() || it.codePointCount(0, it.length) > 120) }) return false
        if (local == null) return zone == null && !remind
        if (zone == null) return false
        return try {
            val date = LocalDateTime.parse(local)
            val tz = ZoneId.of(zone)
            val offsets = tz.rules.getValidOffsets(date)
            date.year in 1..9999 && offsets.size == 1 && run { date.toInstant(offsets.single()).toEpochMilli(); true }
        } catch (_: Exception) { false }
    }

    fun details(task: Task, expectedVersion: Long, actorId: String, commandId: String,
        dueLocal: String?, dueZone: String?, remind: Boolean, person: String?, project: String?): TaskDecision {
        val p = person?.trim()?.takeIf { it.isNotEmpty() }
        val q = project?.trim()?.takeIf { it.isNotEmpty() }
        if (!validIds(actorId, commandId) || !validDetails(dueLocal, dueZone, remind, p, q)) return TaskDecision.Invalid
        if (task.version != expectedVersion) return TaskDecision.Conflict
        if (task.dueLocal == dueLocal && task.dueZone == dueZone && task.remind == remind && task.personLabel == p && task.projectLabel == q) return TaskDecision.Unchanged
        if (task.version == Long.MAX_VALUE) return TaskDecision.Invalid
        return TaskDecision.Changed(TaskEvent.DetailsChanged(task.id, task.scopeId, actorId, commandId, task.version, dueLocal, dueZone, remind, p, q))
    }

    fun edit(task: Task, expectedVersion: Long, actorId: String, commandId: String, title: String,
        dueLocal: String?, dueZone: String?, remind: Boolean, person: String?, project: String?): TaskDecision {
        val normalized = title.trim()
        val p = person?.trim()?.takeIf { it.isNotEmpty() }
        val q = project?.trim()?.takeIf { it.isNotEmpty() }
        if (!validIds(actorId, commandId) || !validTitle(normalized) || !validDetails(dueLocal, dueZone, remind, p, q)) return TaskDecision.Invalid
        if (task.version != expectedVersion) return TaskDecision.Conflict
        if (task.title == normalized && task.dueLocal == dueLocal && task.dueZone == dueZone && task.remind == remind && task.personLabel == p && task.projectLabel == q) return TaskDecision.Unchanged
        if (task.version == Long.MAX_VALUE) return TaskDecision.Invalid
        return TaskDecision.Changed(TaskEvent.Updated(task.id, task.scopeId, actorId, commandId, task.version, normalized, dueLocal, dueZone, remind, p, q))
    }

    fun create(id: String, scopeId: String, actorId: String, commandId: String, title: String): TaskDecision {
        val normalized = title.trim()
        if (!validIds(id, scopeId, actorId, commandId) || !validTitle(normalized)) return TaskDecision.Invalid
        return TaskDecision.Changed(TaskEvent.Created(id, scopeId, actorId, commandId, normalized))
    }

    fun rename(task: Task, expectedVersion: Long, actorId: String, commandId: String, title: String): TaskDecision {
        val normalized = title.trim()
        if (!validIds(actorId, commandId) || !validTitle(normalized)) return TaskDecision.Invalid
        if (task.version != expectedVersion) return TaskDecision.Conflict
        if (task.title == normalized) return TaskDecision.Unchanged
        if (task.version == Long.MAX_VALUE) return TaskDecision.Invalid
        return TaskDecision.Changed(TaskEvent.Renamed(task.id, task.scopeId, actorId, commandId, task.version, normalized))
    }

    fun complete(task: Task, expectedVersion: Long, actorId: String, commandId: String, completed: Boolean): TaskDecision {
        if (!validIds(actorId, commandId)) return TaskDecision.Invalid
        if (task.version != expectedVersion) return TaskDecision.Conflict
        if (task.completed == completed) return TaskDecision.Unchanged
        if (task.version == Long.MAX_VALUE) return TaskDecision.Invalid
        return TaskDecision.Changed(TaskEvent.CompletionChanged(task.id, task.scopeId, actorId, commandId, task.version, completed))
    }

    fun apply(current: Task?, event: TaskEvent): TaskApply {
        if (!validIds(event.taskId, event.scopeId, event.actorId, event.commandId)) return TaskApply.Conflict
        if (event is TaskEvent.Created) {
            if (current != null || !validTitle(event.title)) return TaskApply.Conflict
            return TaskApply.Applied(Task(event.taskId, event.scopeId, event.title, false, 1))
        }
        if (current == null || current.id != event.taskId || current.scopeId != event.scopeId || current.version == Long.MAX_VALUE) {
            return TaskApply.Conflict
        }
        return when (event) {
            is TaskEvent.Renamed -> {
                if (event.previousVersion != current.version || !validTitle(event.title) || event.title == current.title) TaskApply.Conflict
                else TaskApply.Applied(current.copy(title = event.title, version = current.version + 1))
            }
            is TaskEvent.CompletionChanged -> {
                if (event.previousVersion != current.version || event.completed == current.completed) TaskApply.Conflict
                else TaskApply.Applied(current.copy(completed = event.completed, version = current.version + 1))
            }
            is TaskEvent.DetailsChanged -> {
                if (event.previousVersion != current.version || !validDetails(event.dueLocal, event.dueZone, event.remind, event.personLabel, event.projectLabel) ||
                    (current.dueLocal == event.dueLocal && current.dueZone == event.dueZone && current.remind == event.remind && current.personLabel == event.personLabel && current.projectLabel == event.projectLabel)) TaskApply.Conflict
                else TaskApply.Applied(current.copy(dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind,
                    personLabel = event.personLabel, projectLabel = event.projectLabel, version = current.version + 1))
            }
            is TaskEvent.Updated -> {
                if (event.previousVersion != current.version || !validTitle(event.title) ||
                    !validDetails(event.dueLocal, event.dueZone, event.remind, event.personLabel, event.projectLabel) ||
                    (current.title == event.title && current.dueLocal == event.dueLocal && current.dueZone == event.dueZone && current.remind == event.remind && current.personLabel == event.personLabel && current.projectLabel == event.projectLabel)) TaskApply.Conflict
                else TaskApply.Applied(current.copy(title = event.title, dueLocal = event.dueLocal, dueZone = event.dueZone,
                    remind = event.remind, personLabel = event.personLabel, projectLabel = event.projectLabel, version = current.version + 1))
            }
            is TaskEvent.Created -> TaskApply.Conflict
        }
    }
}

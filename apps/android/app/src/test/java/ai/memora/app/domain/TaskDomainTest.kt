package ai.memora.app.domain

import org.junit.Assert.*
import org.junit.Test

class TaskDomainTest {
    private fun create(id: String = "task-1", title: String = "Buy paper"): TaskEvent.Created =
        (TaskRules.create(id, "personal", "owner", "command-create-$id", title) as TaskDecision.Changed).event as TaskEvent.Created

    private fun task(): Task = (TaskRules.apply(null, create()) as TaskApply.Applied).task

    @Test fun createUsesExplicitIdentityAndStartsActive() {
        val event = create(title = "  Buy paper  ")
        val result = (TaskRules.apply(null, event) as TaskApply.Applied).task
        assertEquals("Buy paper", result.title)
        assertEquals("personal", result.scopeId)
        assertEquals("owner", event.actorId)
        assertEquals("command-create-task-1", event.commandId)
        assertEquals(1L, result.version)
        assertFalse(result.completed)
    }

    @Test fun invalidInputsNeverProduceEvents() {
        assertTrue(TaskRules.create("id", "scope", "actor", "cmd", "  ") is TaskDecision.Invalid)
        assertTrue(TaskRules.create("", "scope", "actor", "cmd", "Test") is TaskDecision.Invalid)
        assertTrue(TaskRules.create("id", "", "actor", "cmd", "Test") is TaskDecision.Invalid)
        assertTrue(TaskRules.create("id", "scope", "", "cmd", "Test") is TaskDecision.Invalid)
        assertTrue(TaskRules.create("id", "scope", "actor", "", "Test") is TaskDecision.Invalid)
        assertTrue(TaskRules.create("id", "scope", "actor", "cmd", "x".repeat(201)) is TaskDecision.Invalid)
        assertTrue(TaskRules.create("id", "scope", "actor", "cmd", "😀".repeat(200)) is TaskDecision.Changed)
        assertTrue(TaskRules.create("id", "scope", "actor", "cmd", "😀".repeat(201)) is TaskDecision.Invalid)
    }

    @Test fun changesAreVersionedAndDoNotMutatePriorState() {
        val before = task()
        val renamed = (TaskRules.rename(before, 1, "owner", "rename", "Buy pens") as TaskDecision.Changed).event
        val after = (TaskRules.apply(before, renamed) as TaskApply.Applied).task
        val completed = (TaskRules.complete(after, 2, "owner", "complete", true) as TaskDecision.Changed).event
        val final = (TaskRules.apply(after, completed) as TaskApply.Applied).task
        assertEquals("Buy paper", before.title)
        assertEquals(1L, before.version)
        assertEquals("Buy pens", final.title)
        assertEquals(3L, final.version)
        assertTrue(final.completed)
        assertFalse(after.completed)
    }

    @Test fun staleVersionAndForeignEventAreRejected() {
        val task = task()
        assertTrue(TaskRules.rename(task, 0, "owner", "rename", "New") is TaskDecision.Conflict)
        val other = (TaskRules.apply(null, create("task-2")) as TaskApply.Applied).task
        val foreignEvent = (TaskRules.complete(other, 1, "owner", "complete", true) as TaskDecision.Changed).event
        assertTrue(TaskRules.apply(task, foreignEvent) is TaskApply.Conflict)
        val validEvent = (TaskRules.complete(task, 1, "owner", "complete-2", true) as TaskDecision.Changed).event as TaskEvent.CompletionChanged
        assertTrue(TaskRules.apply(task, validEvent.copy(scopeId = "other-scope")) is TaskApply.Conflict)
        assertTrue(TaskRules.apply(task, validEvent.copy(previousVersion = 0)) is TaskApply.Conflict)
        assertTrue(TaskRules.apply(task, create()) is TaskApply.Conflict)
        assertEquals(1L, task.version)
        assertFalse(task.completed)
    }

    @Test fun unchangedValuesDoNotCreateEvents() {
        val task = task()
        assertTrue(TaskRules.rename(task, 1, "owner", "rename", " Buy paper ") is TaskDecision.Unchanged)
        assertTrue(TaskRules.complete(task, 1, "owner", "complete", false) is TaskDecision.Unchanged)
        assertEquals(1L, task.version)
    }

    @Test fun sameTitleDoesNotMergeDifferentIds() {
        val first = (TaskRules.apply(null, create("first")) as TaskApply.Applied).task
        val second = (TaskRules.apply(null, create("second")) as TaskApply.Applied).task
        assertEquals(first.title, second.title)
        assertNotEquals(first.id, second.id)
    }

    @Test fun detailsPreserveTimeZoneAndLabelsWithoutInventingLinks() {
        val task = task()
        val event = (TaskRules.details(task, 1, "owner", "details", "2026-11-01T09:30", "Asia/Baku", true, "Alex", "Workshop") as TaskDecision.Changed).event
        val updated = (TaskRules.apply(task, event) as TaskApply.Applied).task
        assertEquals("2026-11-01T09:30", updated.dueLocal)
        assertEquals("Asia/Baku", updated.dueZone)
        assertEquals("Alex", updated.personLabel)
        assertEquals("Workshop", updated.projectLabel)
        assertTrue(updated.remind)
        assertEquals(2L, updated.version)
    }

    @Test fun invalidMissingOrAmbiguousDeadlineCannotCreateReminder() {
        val task = task()
        assertTrue(TaskRules.details(task, 1, "owner", "c1", null, null, true, null, null) is TaskDecision.Invalid)
        assertTrue(TaskRules.details(task, 1, "owner", "c1", "2026-03-29T02:30", "Europe/Berlin", true, null, null) is TaskDecision.Invalid)
        assertTrue(TaskRules.details(task, 1, "owner", "c1", "2026-10-25T02:30", "Europe/Berlin", true, null, null) is TaskDecision.Invalid)
        assertTrue(TaskRules.details(task, 1, "owner", "c1", "not-a-date", "Asia/Baku", false, null, null) is TaskDecision.Invalid)
    }
}

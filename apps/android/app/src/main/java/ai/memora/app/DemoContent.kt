package ai.memora.app

import ai.memora.app.ui.Glyph
import androidx.compose.ui.graphics.vector.ImageVector

/** Fictional preview fixtures, never canonical records or AI results. */
data class ExampleMemory(val id: String, val title: Int, val body: Int, val source: Int, val category: Int, val icon: ImageVector, val tone: Int = 0)
val exampleMemories = listOf(
    ExampleMemory("meeting", R.string.sample_meeting, R.string.sample_meeting_body, R.string.source_meeting, R.string.promises, Glyph.Person),
    ExampleMemory("idea", R.string.sample_idea, R.string.sample_idea_body, R.string.source_idea, R.string.ideas, Glyph.Spark, 2),
    ExampleMemory("payment", R.string.sample_payment, R.string.sample_payment_body, R.string.source_payment, R.string.money, Glyph.Wallet, 1),
    ExampleMemory("sensor", R.string.sample_task, R.string.sample_task_body, R.string.source_task, R.string.notes, Glyph.Note),
)
data class ExampleTask(val id: String, val title: Int, val group: Int, val time: String, val upcoming: Boolean = false)
val exampleTasks = listOf(
    ExampleTask("sensor", R.string.sample_task, R.string.work, "10:00"),
    ExampleTask("call", R.string.task_call, R.string.personal, "14:30"),
    ExampleTask("documents", R.string.task_docs, R.string.personal, "18:00", true),
    ExampleTask("meeting", R.string.task_meeting, R.string.work, "11:00", true),
)

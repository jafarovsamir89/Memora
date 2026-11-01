package ai.memora.app.agent

import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.domain.TaskEvent
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/** First production tool slice: bounded evidence search and explicit local memory writes. */
internal class LocalMemoryTools(private val repository: ProtectedDomainRepository, private val memory: ProtectedMemoryRepository) {
    fun handlers(): List<AgentToolHandler> = listOf(SearchMemory(), SaveNote(), ListTables(), CreateTable(), AddTableRow(), ListTasks(), CreateTask())

    private inner class SearchMemory : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_memory", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)

        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val query = call.arguments["query"] as? String ?: return rejected()
            if (query.isBlank() || query.length > 200) return rejected()
            val hits = repository.search(turn.scopeId, query, limit = 12)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "evidence_found", mapOf(
                "count" to hits.size,
                "evidence" to hits.map { hit -> mapOf("id" to hit.objectId, "kind" to hit.kind.name.lowercase(),
                    "title" to hit.title, "text" to hit.evidenceText.take(700), "revision" to hit.evidenceRevisionId) },
            ))
        }

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) =
            ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class SaveNote : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("save_note", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val body = call.arguments["body"] as? String ?: return ToolPreparation.Rejected("invalid_body")
            val kind = when (call.arguments["kind"] as? String) {
                "note" -> DomainKind.NOTE
                "person" -> DomainKind.PERSON
                "project" -> DomainKind.PROJECT
                else -> return ToolPreparation.Rejected("invalid_kind")
            }
            if (title.isBlank() || title.length > 200 || body.isBlank() || body.length > 4000)
                return ToolPreparation.Rejected("invalid_note")
            val objectId = commandId
            val digest = sha256("${kind.name}\u0000$title\u0000$body\u0000$turn.scopeId")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                SavePayload(turn.scopeId, objectId, kind, title.trim(), body.trim())))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? SavePayload ?: return rejected()
            return try {
                val saved = repository.createObject(payload.scope, payload.objectId, payload.kind,
                    payload.title, payload.body, null, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "saved", mapOf("id" to saved.objectId,
                    "kind" to saved.kind.lowercase(), "title" to saved.title, "version" to saved.version))
            } catch (_: Exception) {
                ToolCommitResult(ToolObservationStatus.FAILED, "save_failed")
            }
        }
    }

    private inner class ListTables : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_tables", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val tables = repository.tables(turn.scopeId)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tables_loaded", mapOf("tables" to tables.map { item ->
                mapOf("id" to item.table.tableId, "title" to item.table.title, "schema_version" to item.table.schemaVersion,
                    "columns" to item.columns.map { mapOf("id" to it.columnId, "name" to it.name, "type" to it.valueType) },
                    "rows" to item.rows.take(30).map { it.valuesJson })
            }))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class CreateTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val rawColumns = call.arguments["columns"] as? String ?: return ToolPreparation.Rejected("invalid_columns")
            val names = rawColumns.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
            if (title.isBlank() || title.length > 200 || names.size !in 1..20 || names.any { it.length > 100 })
                return ToolPreparation.Rejected("invalid_table")
            val columns = names.map { ColumnSpec(UUID.randomUUID().toString(), it, ColumnType.TEXT) }
            val id = commandId
            val digest = sha256("table\u0000${turn.scopeId}\u0000$title\u0000${names.joinToString("\u0000")}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                CreateTablePayload(turn.scopeId, id, title.trim(), columns)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateTablePayload ?: return rejected()
            return try {
                repository.createTable(payload.scope, payload.id, payload.title, "editable", payload.columns)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_created", mapOf("id" to payload.id,
                    "title" to payload.title, "columns" to payload.columns.map { mapOf("id" to it.id, "name" to it.name) }))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "table_create_failed") }
        }
    }

    private inner class AddTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("add_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val raw = call.arguments["values_json"] as? String ?: return ToolPreparation.Rejected("invalid_values")
            if (raw.length > 4000) return ToolPreparation.Rejected("row_too_large")
            val snapshot = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolPreparation.Rejected("table_not_found")
            val parsed = runCatching { JSONObject(raw) }.getOrElse { return ToolPreparation.Rejected("invalid_values") }
            val byName = snapshot.columns.associateBy { it.name.lowercase() }
            val values = linkedMapOf<String, Any?>()
            for (key in parsed.keys()) {
                val column = byName[key.lowercase()] ?: return ToolPreparation.Rejected("unknown_column")
                val rawValue = parsed.opt(key)
                values[column.columnId] = when (rawValue) {
                    JSONObject.NULL -> null
                    is String -> rawValue
                    is Number, is Boolean -> rawValue.toString()
                    else -> return ToolPreparation.Rejected("nested_cell_value_not_supported")
                }
            }
            val rowId = commandId
            val digest = sha256("row\u0000$tableId\u0000$raw")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest,
                mapOf(tableId to snapshot.table.schemaVersion), false,
                AddRowPayload(turn.scopeId, tableId, snapshot.table.schemaVersion, rowId, values)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? AddRowPayload ?: return rejected()
            return try {
                val rows = repository.addRows(payload.scope, payload.tableId, payload.schemaVersion, listOf(payload.rowId to payload.values))
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_added", mapOf("table_id" to payload.tableId,
                    "row_id" to rows.single().rowId, "values" to rows.single().valuesJson))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "row_add_failed") }
        }
    }

    private inner class ListTasks : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_tasks", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val snapshot = memory.tasks()
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tasks_loaded", mapOf("tasks" to snapshot.tasks
                .filter { it.scopeId == turn.scopeId }.take(100).map { mapOf("id" to it.id, "title" to it.title,
                    "completed" to it.completed, "due_local" to it.dueLocal) }))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class CreateTask : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_task", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            if (title.isBlank() || title.length > 200) return ToolPreparation.Rejected("invalid_task")
            val id = commandId
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("task\u0000${turn.scopeId}\u0000$id\u0000$title"), emptyMap(), false,
                CreateTaskPayload(id, turn.scopeId, turn.actorId, commandId, title.trim())))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateTaskPayload ?: return rejected()
            return try {
                val task = memoryTask(payload)
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_created", mapOf("id" to task.id, "title" to task.title))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "task_create_failed") }
        }
        private suspend fun memoryTask(payload: CreateTaskPayload) = repository.appendTaskEvent(TaskEvent.Created(
            payload.taskId, payload.scope, payload.actor, payload.commandId, payload.title))
    }

    private data class SavePayload(val scope: String, val objectId: String,
        val kind: DomainKind, val title: String, val body: String)
    private data class CreateTablePayload(val scope: String, val id: String, val title: String, val columns: List<ColumnSpec>)
    private data class AddRowPayload(val scope: String, val tableId: String, val schemaVersion: Long,
        val rowId: String, val values: Map<String, Any?>)
    private data class CreateTaskPayload(val taskId: String, val scope: String, val actor: String,
        val commandId: String, val title: String)

    private fun rejected() = ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_arguments")
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

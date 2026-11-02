package ai.memora.app.agent

import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.TableColumnRow
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedConversationRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.domain.TaskEvent
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskDecision
import ai.memora.app.domain.TaskRules
import ai.memora.app.domain.VoiceMoneyRules
import java.security.MessageDigest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** First production tool slice: bounded evidence search and explicit local memory writes. */
internal class LocalMemoryTools(private val repository: ProtectedDomainRepository, private val memory: ProtectedMemoryRepository,
    private val financeEnabled: Boolean = true, private val onTaskChanged: (Task) -> String = { "not_scheduled" },
    private val conversations: ProtectedConversationRepository? = null) {
    fun handlers(): List<AgentToolHandler> = listOf(
        SearchMemory(), SaveNote(), EditMemoryObject(), DeleteMemoryObject(), RestoreMemoryObject(), LinkObjects(), ListTables(), ReadTableRows(), CreateTable(), AddTableRow(),
        UpdateTableRow(), DeleteTableRow(), RestoreTableRow(), RenameTable(), DeleteTable(), RestoreTable(),
        SearchConversationHistory(), ListTasks(), CreateTask(), EditTask(), SetTaskCompletion(), ListCalendarEvents(), CreateCalendarEvent(),
        EditCalendarEvent(), CancelCalendarEvent(),
        SearchMoney(), RecordMoneyEvent(), SettleDebt(), ReverseMoneyEvent(), CorrectMoneyEvent(), AnnotateMoneyEvent(),
    )

    private inner class SearchMemory : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_memory", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)

        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val query = call.arguments["query"] as? String ?: return rejected()
            if (query.isBlank() || query.length > 200) return rejected()
            val hits = repository.search(turn.scopeId, query, limit = 8)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "evidence_found", mapOf(
                "count" to hits.size,
                "evidence" to hits.map { hit -> mapOf("id" to hit.objectId, "kind" to hit.kind.name.lowercase(),
                    "title" to hit.title, "text" to hit.evidenceText.take(500),
                    "text_truncated" to (hit.evidenceText.length > 500), "revision" to hit.evidenceRevisionId) },
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

    private inner class EditMemoryObject : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_memory_object", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["object_id"] as? String ?: return ToolPreparation.Rejected("invalid_object_id")
            val current = repository.domainObject(turn.scopeId, id)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("object_not_found")
            val title = (call.arguments["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: current.title
            val body = (call.arguments["body"] as? String) ?: current.body.orEmpty()
            if (title.length > 200 || body.length > 100_000 || (title == current.title && body == current.body.orEmpty()))
                return ToolPreparation.Rejected("empty_or_invalid_edit")
            val digest = sha256("object_edit\u0000${turn.scopeId}\u0000$id\u0000${current.version}\u0000$title\u0000$body")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to current.version), false,
                EditObjectPayload(turn.scopeId, id, current.version, title, body, current.status)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? EditObjectPayload ?: return rejected()
            return try {
                val updated = repository.updateObject(payload.scope, payload.id, payload.version, payload.title,
                    payload.body, payload.status, null, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "memory_object_updated", mapOf("id" to updated.objectId,
                    "title" to updated.title, "version" to updated.version))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "memory_update_conflict") }
        }
    }

    private inner class DeleteMemoryObject : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_memory_object", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["object_id"] as? String ?: return ToolPreparation.Rejected("invalid_object_id")
            val current = repository.domainObject(turn.scopeId, id)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("object_not_found")
            val preview = runCatching { repository.previewDelete(turn.scopeId, id, current.version) }.getOrNull()
                ?: return ToolPreparation.Rejected("object_delete_conflict")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("object_delete\u0000${turn.scopeId}\u0000$id\u0000${current.version}"),
                mapOf(id to current.version), false, DeleteObjectPayload(turn.scopeId, preview)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteObjectPayload ?: return rejected()
            return try {
                repository.delete(payload.scope, payload.preview, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "memory_object_deleted", mapOf(
                    "object_id" to payload.preview.objectId, "undo_available" to true,
                    "linked_objects" to payload.preview.linkedIds))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "memory_delete_conflict") }
        }
    }

    private inner class RestoreMemoryObject : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("restore_memory_object", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["object_id"] as? String ?: return ToolPreparation.Rejected("invalid_object_id")
            val current = repository.domainObject(turn.scopeId, id) ?: return ToolPreparation.Rejected("object_not_found")
            if (current.deletedAt == null) return ToolPreparation.Rejected("object_not_deleted")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("object_restore\u0000${turn.scopeId}\u0000$id\u0000${current.version}"),
                mapOf(id to current.version), false, RestoreObjectPayload(turn.scopeId, id, current.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? RestoreObjectPayload ?: return rejected()
            return try {
                val restored = repository.restore(payload.scope, payload.id, payload.version, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "memory_object_restored", mapOf("id" to restored.objectId, "title" to restored.title))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "memory_restore_conflict") }
        }
    }

    private inner class ListTables : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_tables", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val tables = repository.tables(turn.scopeId)
            val selected = tables.take(8)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tables_loaded", mapOf(
                "table_count" to tables.size, "truncated" to (tables.size > selected.size),
                "tables" to selected.map { item -> mapOf("id" to item.table.tableId, "title" to item.table.title.take(120),
                    "schema_version" to item.table.schemaVersion, "row_count" to item.rows.size,
                    "schema_truncated" to (item.columns.size > 8),
                    "columns" to item.columns.take(8).map { mapOf("id" to it.columnId, "name" to it.name.take(80), "type" to it.valueType) }) }
            ))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class ReadTableRows : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("read_table_rows", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val tableId = call.arguments["table_id"] as? String ?: return rejected()
            val query = call.arguments["query"] as? String ?: ""
            if (tableId.isBlank() || query.length > 200) return rejected()
            val table = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolCommitResult(ToolObservationStatus.REJECTED, "table_not_found")
            val matchingRows = table.rows.filter { query.isBlank() || it.valuesJson.contains(query, ignoreCase = true) }
            val rows = matchingRows.take(10).map { row -> mapOf("id" to row.rowId, "version" to row.version,
                "values" to row.valuesJson.take(700), "values_truncated" to (row.valuesJson.length > 700)) }
            return ToolCommitResult(ToolObservationStatus.APPLIED, "table_rows_loaded", mapOf(
                "table_id" to tableId, "title" to table.table.title.take(120),
                "columns" to table.columns.take(12).map { mapOf("id" to it.columnId, "name" to it.name.take(80), "type" to it.valueType) },
                "rows" to rows, "returned" to rows.size, "matching_row_count" to matchingRows.size,
                "truncated" to (matchingRows.size > rows.size),
            ))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class LinkObjects : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("link_objects", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val from = call.arguments["from_id"] as? String ?: return ToolPreparation.Rejected("invalid_from_id")
            val to = call.arguments["to_id"] as? String ?: return ToolPreparation.Rejected("invalid_to_id")
            val relation = call.arguments["relation"] as? String ?: return ToolPreparation.Rejected("invalid_relation")
            if (from.isBlank() || to.isBlank() || relation !in setOf("related_to", "works_on", "owes", "family_of", "part_of"))
                return ToolPreparation.Rejected("invalid_link")
            val digest = sha256("link\u0000${turn.scopeId}\u0000$from\u0000$to\u0000$relation")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                LinkPayload(turn.scopeId, commandId, from, to, relation)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? LinkPayload ?: return rejected()
            return try {
                repository.link(payload.scope, payload.id, payload.from, payload.to, payload.relation)
                ToolCommitResult(ToolObservationStatus.APPLIED, "objects_linked", mapOf("relation" to payload.relation))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "link_failed") }
        }
    }

    private inner class CreateTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val rawColumns = (call.arguments["columns"] as? String).orEmpty()
            val rawSchema = (call.arguments["columns_json"] as? String)?.takeIf { it.isNotBlank() }
            val columns = if (rawSchema != null) {
                if (rawSchema.length > 6000) return ToolPreparation.Rejected("schema_too_large")
                val array = runCatching { org.json.JSONArray(rawSchema) }.getOrElse { return ToolPreparation.Rejected("invalid_schema") }
                if (array.length() !in 1..50) return ToolPreparation.Rejected("invalid_schema")
                (0 until array.length()).map { index ->
                    val item = runCatching { array.getJSONObject(index) }.getOrElse { return ToolPreparation.Rejected("invalid_schema") }
                    val name = item.optString("name").trim()
                    val type = runCatching { ColumnType.valueOf(item.optString("type", "TEXT").uppercase()) }.getOrNull()
                        ?: return ToolPreparation.Rejected("invalid_column_type")
                    if (type !in setOf(ColumnType.TEXT, ColumnType.INTEGER, ColumnType.DECIMAL, ColumnType.BOOLEAN,
                            ColumnType.DATE, ColumnType.DATETIME, ColumnType.MONEY) || name.isBlank() || name.length > 100)
                        return ToolPreparation.Rejected("invalid_column")
                    ColumnSpec(UUID.randomUUID().toString(), name, type, item.optBoolean("required", false))
                }
            } else {
                val names = rawColumns.split(',').map(String::trim).filter(String::isNotEmpty)
                if (names.size !in 1..20 || names.any { it.length > 100 }) return ToolPreparation.Rejected("invalid_table")
                names.map { ColumnSpec(UUID.randomUUID().toString(), it, ColumnType.TEXT) }
            }
            if (title.isBlank() || title.length > 200 || columns.map { it.name.lowercase() }.distinct().size != columns.size)
                return ToolPreparation.Rejected("invalid_table")
            val id = commandId
            val digest = sha256("table\u0000${turn.scopeId}\u0000$title\u0000${columns.joinToString("\u0000") { "${it.name}:${it.type}:${it.required}" }}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                CreateTablePayload(turn.scopeId, id, title.trim(), columns)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateTablePayload ?: return rejected()
            return try {
                repository.createTable(payload.scope, payload.id, payload.title, "editable", payload.columns)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_created", mapOf("id" to payload.id,
                    "title" to payload.title, "columns" to payload.columns.map { mapOf("id" to it.id, "name" to it.name, "type" to it.type.name, "required" to it.required) }))
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
                val parsedCell = parseTableCell(column, rawValue)
                if (!parsedCell.valid) return ToolPreparation.Rejected("invalid_cell_value")
                values[column.columnId] = parsedCell.value
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

    private inner class UpdateTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("update_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val rowId = call.arguments["row_id"] as? String ?: return ToolPreparation.Rejected("invalid_row_id")
            val raw = call.arguments["values_json"] as? String ?: return ToolPreparation.Rejected("invalid_values")
            if (raw.length > 4000) return ToolPreparation.Rejected("row_too_large")
            val table = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolPreparation.Rejected("table_not_found")
            if (table.table.mode != "editable") return ToolPreparation.Rejected("linked_view_read_only")
            val row = table.rows.firstOrNull { it.rowId == rowId } ?: return ToolPreparation.Rejected("row_not_found")
            val parsed = runCatching { JSONObject(raw) }.getOrElse { return ToolPreparation.Rejected("invalid_values") }
            if (parsed.length() == 0) return ToolPreparation.Rejected("empty_update")
            val byName = table.columns.associateBy { it.name.lowercase() }
            val merged = JSONObject(row.valuesJson)
            for (key in parsed.keys()) {
                val column = byName[key.lowercase()] ?: return ToolPreparation.Rejected("unknown_column")
                val value = parsed.opt(key)
                val parsedCell = parseTableCell(column, value)
                if (!parsedCell.valid) return ToolPreparation.Rejected("invalid_cell_value")
                merged.put(column.columnId, parsedCell.value ?: JSONObject.NULL)
            }
            val values = merged.keys().asSequence().associateWith { key ->
                val value = merged.opt(key)
                when (value) {
                    JSONObject.NULL -> null
                    is JSONObject -> mapOf("minorUnits" to value.getLong("minorUnits"), "currency" to value.getString("currency"))
                    is Map<*, *> -> value
                    else -> value
                }
            }
            val digest = sha256("table_row_update\u0000${turn.scopeId}\u0000$tableId\u0000$rowId\u0000${row.version}\u0000$raw")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest,
                mapOf(tableId to table.table.schemaVersion, rowId to row.version), false,
                UpdateRowPayload(turn.scopeId, tableId, rowId, row.version, table.table.schemaVersion, values)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? UpdateRowPayload ?: return rejected()
            return try {
                val row = repository.updateRow(payload.scope, payload.tableId, payload.rowId, payload.rowVersion,
                    payload.schemaVersion, payload.values)
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_updated", mapOf("table_id" to payload.tableId,
                    "row_id" to row.rowId, "version" to row.version, "values" to row.valuesJson))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "row_update_conflict") }
        }
    }

    private inner class DeleteTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val rowId = call.arguments["row_id"] as? String ?: return ToolPreparation.Rejected("invalid_row_id")
            val table = repository.tables(turn.scopeId).firstOrNull { it.table.tableId == tableId }
                ?: return ToolPreparation.Rejected("table_not_found")
            val row = table.rows.firstOrNull { it.rowId == rowId } ?: return ToolPreparation.Rejected("row_not_found")
            val digest = sha256("table_row_delete\u0000${turn.scopeId}\u0000$tableId\u0000$rowId\u0000${row.version}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(rowId to row.version), false,
                DeleteRowPayload(turn.scopeId, tableId, rowId, row.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteRowPayload ?: return rejected()
            return try {
                repository.deleteRow(payload.scope, payload.tableId, payload.rowId, payload.version, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_deleted", mapOf("row_id" to payload.rowId, "undo_available" to true))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "row_delete_conflict") }
        }
    }

    private inner class RestoreTableRow : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("restore_table_row", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val rowId = call.arguments["row_id"] as? String ?: return ToolPreparation.Rejected("invalid_row_id")
            val row = repository.tableRow(turn.scopeId, tableId, rowId) ?: return ToolPreparation.Rejected("row_not_found")
            if (row.deletedAt == null) return ToolPreparation.Rejected("row_not_deleted")
            val digest = sha256("table_row_restore\u0000${turn.scopeId}\u0000$tableId\u0000$rowId\u0000${row.version}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(rowId to row.version), false,
                DeleteRowPayload(turn.scopeId, tableId, rowId, row.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? DeleteRowPayload ?: return rejected()
            return try {
                repository.restoreRow(payload.scope, payload.tableId, payload.rowId, payload.version)
                ToolCommitResult(ToolObservationStatus.APPLIED, "row_restored", mapOf("row_id" to payload.rowId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "row_restore_conflict") }
        }
    }

    private inner class RenameTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("rename_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val table = repository.table(turn.scopeId, tableId)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("table_not_found")
            if (title.isBlank() || title.length > 200) return ToolPreparation.Rejected("invalid_title")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("table_rename\u0000${turn.scopeId}\u0000$tableId\u0000${table.version}\u0000$title"),
                mapOf(tableId to table.version), false, RenameTablePayload(turn.scopeId, tableId, table.version, title.trim())))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? RenameTablePayload ?: return rejected()
            return try {
                val updated = repository.renameTable(payload.scope, payload.tableId, payload.version, payload.title)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_renamed", mapOf("id" to updated.tableId, "title" to updated.title))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "table_rename_conflict") }
        }
    }

    private inner class DeleteTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("delete_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val table = repository.table(turn.scopeId, tableId)?.takeIf { it.deletedAt == null }
                ?: return ToolPreparation.Rejected("table_not_found")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("table_delete\u0000${turn.scopeId}\u0000$tableId\u0000${table.version}"),
                mapOf(tableId to table.version), false, TableLifecyclePayload(turn.scopeId, tableId, table.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? TableLifecyclePayload ?: return rejected()
            return try {
                repository.deleteTable(payload.scope, payload.tableId, payload.version, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_deleted", mapOf("table_id" to payload.tableId, "undo_available" to true))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "table_delete_conflict") }
        }
    }

    private inner class RestoreTable : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("restore_table", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val tableId = call.arguments["table_id"] as? String ?: return ToolPreparation.Rejected("invalid_table_id")
            val table = repository.table(turn.scopeId, tableId) ?: return ToolPreparation.Rejected("table_not_found")
            if (table.deletedAt == null) return ToolPreparation.Rejected("table_not_deleted")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("table_restore\u0000${turn.scopeId}\u0000$tableId\u0000${table.version}"),
                mapOf(tableId to table.version), false, TableLifecyclePayload(turn.scopeId, tableId, table.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? TableLifecyclePayload ?: return rejected()
            return try {
                repository.restoreTable(payload.scope, payload.tableId, payload.version)
                ToolCommitResult(ToolObservationStatus.APPLIED, "table_restored", mapOf("table_id" to payload.tableId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "table_restore_conflict") }
        }
    }

    private inner class ListTasks : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_tasks", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val snapshot = memory.tasks()
            val matching = snapshot.tasks.filter { it.scopeId == turn.scopeId }
            val selected = matching.take(16)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tasks_loaded", mapOf("tasks" to selected.map { mapOf("id" to it.id, "title" to it.title.take(160),
                    "completed" to it.completed, "due_local" to it.dueLocal, "due_zone" to it.dueZone,
                    "remind" to it.remind, "person" to it.personLabel?.take(80), "project" to it.projectLabel?.take(80)) },
                "task_count" to matching.size, "truncated" to (matching.size > selected.size)))
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
            val dueLocal = (call.arguments["due_local"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val dueZone = (call.arguments["zone_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Asia/Baku"
            val remind = call.arguments["remind"] as? Boolean ?: false
            val person = (call.arguments["person_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val project = (call.arguments["project_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (remind && dueLocal == null) return ToolPreparation.Rejected("reminder_requires_due_time")
            val preview = TaskRules.create(commandId, turn.scopeId, turn.actorId, commandId, title)
            if (preview !is TaskDecision.Changed) return ToolPreparation.Rejected("invalid_task")
            if (dueLocal != null && TaskRules.details(
                    (TaskRules.apply(null, preview.event) as? ai.memora.app.domain.TaskApply.Applied)?.task ?: return ToolPreparation.Rejected("invalid_task"),
                    1, turn.actorId, UUID.randomUUID().toString(), dueLocal, dueZone, remind, person, project) !is TaskDecision.Changed)
                return ToolPreparation.Rejected("invalid_task_details")
            val id = commandId
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("task\u0000${turn.scopeId}\u0000$id\u0000$title\u0000$dueLocal\u0000$dueZone\u0000$remind\u0000$person\u0000$project"), emptyMap(), false,
                CreateTaskPayload(id, turn.scopeId, turn.actorId, commandId, title.trim(), dueLocal, dueZone, remind, person, project)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CreateTaskPayload ?: return rejected()
            return try {
                val task = memoryTask(payload)
                val reminder = if (payload.remind) runCatching { onTaskChanged(task) }.getOrDefault("unavailable") else "not_requested"
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_created", mapOf("id" to task.id, "title" to task.title,
                    "due_local" to task.dueLocal, "due_zone" to task.dueZone, "remind" to task.remind, "reminder_status" to reminder))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "task_create_failed") }
        }
        private suspend fun memoryTask(payload: CreateTaskPayload): Task {
            val created = TaskEvent.Created(payload.taskId, payload.scope, payload.actor, payload.commandId, payload.title)
            val events = mutableListOf<TaskEvent>(created)
            if (payload.dueLocal != null) events += TaskEvent.DetailsChanged(payload.taskId, payload.scope, payload.actor,
                UUID.randomUUID().toString(), 1, payload.dueLocal, payload.dueZone, payload.remind, payload.person, payload.project)
            return repository.appendTaskEvents(events)
        }
    }

    private inner class SetTaskCompletion : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("set_task_completion", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val taskId = call.arguments["task_id"] as? String ?: return ToolPreparation.Rejected("invalid_task_id")
            val completed = call.arguments["completed"] as? Boolean ?: return ToolPreparation.Rejected("invalid_completion_state")
            val task = memory.tasks().tasks.firstOrNull { it.id == taskId && it.scopeId == turn.scopeId }
                ?: return ToolPreparation.Rejected("task_not_found")
            if (task.completed == completed) return ToolPreparation.Rejected("task_already_in_requested_state")
            val digest = sha256("task_completion\u0000${turn.scopeId}\u0000$taskId\u0000${task.version}\u0000$completed")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(taskId to task.version), false,
                CompletionPayload(task.id, turn.scopeId, turn.actorId, commandId, task.version, completed)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CompletionPayload ?: return rejected()
            return try {
                val task = repository.appendTaskEvent(TaskEvent.CompletionChanged(payload.id, payload.scope, payload.actor,
                    payload.commandId, payload.version, payload.completed))
                val reminder = runCatching { onTaskChanged(task) }.getOrDefault("unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_completion_changed", mapOf(
                    "id" to task.id, "title" to task.title, "completed" to task.completed, "version" to task.version,
                    "reminder_status" to reminder))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "task_update_conflict") }
        }
    }

    private inner class EditTask : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_task", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val id = call.arguments["task_id"] as? String ?: return ToolPreparation.Rejected("invalid_task_id")
            val task = memory.tasks().tasks.firstOrNull { it.id == id && it.scopeId == turn.scopeId }
                ?: return ToolPreparation.Rejected("task_not_found")
            val clearDue = call.arguments["clear_due"] as? Boolean ?: false
            val due = if (clearDue) null else (call.arguments["due_local"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: task.dueLocal
            val zone = if (due == null) null else (call.arguments["zone_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: task.dueZone ?: "Asia/Baku"
            val remind = if (due == null) false else call.arguments["remind"] as? Boolean ?: task.remind
            val title = (call.arguments["title"] as? String)?.takeIf { it.isNotBlank() } ?: task.title
            val person = (call.arguments["person_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: task.personLabel.takeUnless { call.arguments["person_label"] is String }
            val project = (call.arguments["project_label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: task.projectLabel.takeUnless { call.arguments["project_label"] is String }
            val decision = TaskRules.edit(task, task.version, turn.actorId, commandId, title, due, zone, remind, person, project)
            val event = (decision as? TaskDecision.Changed)?.event ?: return ToolPreparation.Rejected("task_unchanged_or_invalid")
            val digest = sha256("task_edit\u0000${turn.scopeId}\u0000$id\u0000${task.version}\u0000$event")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(id to task.version), false,
                TaskEditPayload(event)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? TaskEditPayload ?: return rejected()
            return try {
                val task = repository.appendTaskEvent(payload.event)
                val reminder = runCatching { onTaskChanged(task) }.getOrDefault("unavailable")
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_updated", mapOf("id" to task.id, "title" to task.title,
                    "due_local" to task.dueLocal, "due_zone" to task.dueZone, "remind" to task.remind, "reminder_status" to reminder))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "task_update_conflict") }
        }
    }

    private inner class ListCalendarEvents : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("list_calendar_events", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = true)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val from = call.arguments["from_local"] as? String ?: return rejected()
            val until = call.arguments["until_local"] as? String ?: return rejected()
            if (from.length > 30 || until.length > 30 || from >= until) return rejected()
            return try {
                val matching = repository.calendarBetween(turn.scopeId, from, until)
                val events = matching.take(16)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_loaded", mapOf("events" to events.map { event ->
                    mapOf("id" to event.eventId, "title" to event.title.take(160), "start_local" to event.startLocal,
                        "end_local" to event.endLocal, "zone_id" to event.zoneId, "precision" to event.timePrecision)
                }, "event_count" to matching.size, "truncated" to (matching.size > events.size)))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_calendar_range") }
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class SearchConversationHistory : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_conversation_history", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = conversations != null)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val query = (call.arguments["query"] as? String)?.trim().orEmpty()
            val daysBack = (call.arguments["days_back"] as? Number)?.toInt()
            val limit = (call.arguments["max_results"] as? Number)?.toInt() ?: 8
            if (query.length > 300 || (query.isBlank() && daysBack == null) || (daysBack != null && daysBack !in 1..365) || limit !in 1..8)
                return rejected()
            return try {
                val hits = conversations?.search(turn.scopeId, query, daysBack, limit).orEmpty()
                ToolCommitResult(ToolObservationStatus.APPLIED, "conversation_evidence_loaded", mapOf(
                    "searched_days_back" to daysBack, "count" to hits.size,
                    "evidence" to hits.map { hit -> mapOf("turn_id" to hit.turnId, "speaker" to hit.role,
                        "time_epoch_ms" to hit.createdAtMillis, "relevance" to hit.score, "snippet" to hit.snippet.take(600),
                        "snippet_truncated" to (hit.snippet.length > 600)) }))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.FAILED, "conversation_search_failed") }
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class CreateCalendarEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("create_calendar_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val title = call.arguments["title"] as? String ?: return ToolPreparation.Rejected("invalid_title")
            val start = call.arguments["start_local"] as? String ?: return ToolPreparation.Rejected("invalid_start")
            val end = call.arguments["end_local"] as? String
            val zone = call.arguments["zone_id"] as? String ?: return ToolPreparation.Rejected("invalid_zone")
            val precision = call.arguments["precision"] as? String ?: "minute"
            if (title.isBlank() || title.length > 200 || start.length > 30 || (end?.length ?: 0) > 30 || zone.length > 80 || precision !in setOf("date", "minute"))
                return ToolPreparation.Rejected("invalid_calendar_event")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("calendar\u0000${turn.scopeId}\u0000$title\u0000$start\u0000$end\u0000$zone\u0000$precision"), emptyMap(), false,
                CalendarPayload(CalendarInput(commandId, turn.scopeId, title.trim(), start, end, zone, precision))))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CalendarPayload ?: return rejected()
            return try {
                val event = repository.createCalendarEvent(payload.input)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_event_created", mapOf("id" to event.eventId,
                    "title" to event.title, "start_local" to event.startLocal, "zone_id" to event.zoneId))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_calendar_event") }
        }
    }

    private inner class EditCalendarEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("edit_calendar_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val current = repository.calendarEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("calendar_event_not_found")
            if (current.status != "active") return ToolPreparation.Conflict("calendar_event_cancelled")
            val clearEnd = call.arguments["clear_end"] as? Boolean ?: false
            val endArgument = call.arguments["end_local"] as? String
            if (clearEnd && endArgument != null) return ToolPreparation.Rejected("invalid_calendar_end")
            val input = CalendarInput(eventId, turn.scopeId,
                (call.arguments["title"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.title,
                (call.arguments["start_local"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.startLocal,
                if (clearEnd) null else endArgument ?: current.endLocal,
                (call.arguments["zone_id"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.zoneId,
                (call.arguments["precision"] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: current.timePrecision)
            if (input.title.length > 200 || input.startLocal.length > 30 || (input.endLocal?.length ?: 0) > 30 || input.zoneId.length > 80 ||
                input.precision !in setOf("date", "minute")) return ToolPreparation.Rejected("invalid_calendar_event")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("calendar_edit\u0000${turn.scopeId}\u0000$eventId\u0000${current.version}\u0000${input.hashCode()}"),
                mapOf(eventId to current.version), false, CalendarEditPayload(input, current.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CalendarEditPayload ?: return rejected()
            return try {
                val event = repository.updateCalendarEvent(payload.input.scopeId, payload.input, payload.expectedVersion)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_event_updated", mapOf("id" to event.eventId,
                    "title" to event.title, "start_local" to event.startLocal, "end_local" to event.endLocal,
                    "zone_id" to event.zoneId, "precision" to event.timePrecision))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "calendar_event_update_conflict") }
        }
    }

    private inner class CancelCalendarEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("cancel_calendar_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = true)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val current = repository.calendarEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("calendar_event_not_found")
            if (current.status != "active") return ToolPreparation.Conflict("calendar_event_already_cancelled")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId,
                sha256("calendar_cancel\u0000${turn.scopeId}\u0000$eventId\u0000${current.version}"),
                mapOf(eventId to current.version), false, CalendarCancelPayload(turn.scopeId, eventId, current.version)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? CalendarCancelPayload ?: return rejected()
            return try {
                val event = repository.cancelCalendarEvent(payload.scope, payload.eventId, payload.expectedVersion)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_event_cancelled", mapOf("id" to event.eventId,
                    "title" to event.title, "start_local" to event.startLocal, "status" to event.status))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.CONFLICT, "calendar_event_cancel_conflict") }
        }
    }

    private inner class SearchMoney : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_money", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = financeEnabled)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val name = (call.arguments["person_name"] as? String)?.trim().orEmpty()
            val query = (call.arguments["query"] as? String)?.trim().orEmpty()
            val kind = (call.arguments["kind"] as? String)?.takeIf { it.isNotBlank() }
            if ((name.isBlank() && query.isBlank()) || name.length > 100 || query.length > 200 ||
                (kind != null && kind !in setOf("receipt", "debt", "expense"))) return rejected()
            val history = try { if (name.isNotBlank()) repository.financeHistory(turn.scopeId, name) else null }
            catch (_: IllegalStateException) { return ToolCommitResult(ToolObservationStatus.CONFLICT, "person_name_ambiguous") }
            val names = repository.financePeople(turn.scopeId).associate { it.personId to it.displayName }
            val matchingEvents = (if (name.isNotBlank()) history?.events.orEmpty() else repository.allFinanceEvents(turn.scopeId).filter { event ->
                listOfNotNull(names[event.personId], event.comment, event.source).any { it.contains(query, ignoreCase = true) }
            }).filter { kind == null || it.kind == kind }
            val events = matchingEvents.asReversed().take(12)
            val adjustments = repository.financeAdjustments(turn.scopeId)
            val eventData = events.map { event ->
                val annotations = adjustments.filter { it.kind == "annotation" && it.reversesEventId == event.eventId }
                    .sortedByDescending { it.occurredAt }
                mapOf("event_id" to event.eventId, "kind" to event.kind,
                "person_name" to names[event.personId], "minor_units" to event.minorUnits, "currency" to event.currency,
                "remaining_minor_units" to history?.remainingDebtMinorByEvent?.get(event.eventId),
                "applied_to_debt_minor_units" to if (event.kind == "receipt") repository.appliedToDebtMinor(turn.scopeId, event.eventId) else null,
                "voided" to (history?.voidedEventIds?.contains(event.eventId) ?: repository.isFinanceEventVoided(turn.scopeId, event.eventId)),
                "recorded_at" to event.recordedAt, "occurred_at" to event.occurredAt,
                "annotations" to annotations.take(4).map { it.reason },
                "annotation_count" to annotations.size, "annotations_truncated" to (annotations.size > 4),
                "comment" to event.comment?.take(250), "comment_truncated" to ((event.comment?.length ?: 0) > 250),
                "source" to event.source.take(300), "source_truncated" to (event.source.length > 300)) }
            val financialSummary = history?.let { personHistory ->
                val active = personHistory.events.filterNot { it.eventId in personHistory.voidedEventIds }
                mapOf("currency" to "AZN",
                    "received_minor_units" to active.filter { it.kind == "receipt" }.fold(0L) { total, event -> Math.addExact(total, event.minorUnits) },
                    "spent_minor_units" to active.filter { it.kind == "expense" }.fold(0L) { total, event -> Math.addExact(total, event.minorUnits) },
                    "outstanding_debt_minor_units" to personHistory.remainingDebtMinorByEvent.values.fold(0L) { total, amount -> Math.addExact(total, amount) })
            }
            return ToolCommitResult(ToolObservationStatus.APPLIED, "financial_evidence_loaded", boundedFinanceEvidence(mapOf(
                "person_found" to (history != null), "person_name" to history?.person?.displayName,
                "event_order" to "recorded_at_desc", "financial_summary" to financialSummary,
            ), eventData, matchingEvents.size))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class RecordMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("record_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val kind = call.arguments["kind"] as? String ?: return ToolPreparation.Rejected("invalid_kind")
            val personName = (call.arguments["person_name"] as? String)?.trim().orEmpty()
            val amount = moneyAmountArgument(call.arguments["amount"]) ?: return ToolPreparation.Rejected("invalid_amount")
            val currency = (call.arguments["currency"] as? String)?.uppercase() ?: "AZN"
            val comment = (call.arguments["comment"] as? String)?.takeIf { it.isNotBlank() }
            val localDate = (call.arguments["occurred_date"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if ((kind != "expense" && personName.isBlank()) || personName.length > 100 || amount.length > 32 || VoiceMoneyRules.parseMinor(amount) == null ||
                currency != "AZN" || (call.arguments["occurred_date"] as? String)?.isNotBlank() == true && localDate == null ||
                (comment?.length ?: 0) > 1000)
                return ToolPreparation.Rejected("invalid_money_event")
            val personMatches = try { if (personName.isBlank()) emptyList() else repository.matchingFinancePeople(turn.scopeId, personName) }
                catch (_: Exception) { return ToolPreparation.Rejected("finance_store_unavailable") }
            if (personMatches.size > 1) return ToolPreparation.Conflict("person_name_ambiguous")
            val personId = personMatches.singleOrNull()?.personId ?: personName.takeIf { it.isNotBlank() }?.let { UUID.randomUUID().toString() }.orEmpty()
            val eventId = UUID.randomUUID().toString()
            val recordedAt = Instant.now()
            val occurredAt = (localDate ?: recordedAt.atZone(ZoneId.of("Asia/Baku")).toLocalDate())
                .atStartOfDay(ZoneId.of("Asia/Baku")).toInstant()
            val digest = sha256("money\u0000${turn.scopeId}\u0000$kind\u0000${VoiceMoneyRules.key(personName)}\u0000$amount\u0000$currency\u0000$occurredAt\u0000${comment.orEmpty()}\u0000${turn.text}")
            if (kind !in setOf("receipt", "debt", "expense")) return ToolPreparation.Rejected("invalid_kind")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                FinancePayload(turn.scopeId, personName.trim(), kind, amount, currency, turn.text, comment,
                    personId, eventId, commandId, recordedAt, occurredAt)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? FinancePayload ?: return rejected()
            return try {
                val event = repository.recordFinanceEvent(payload.scope, payload.personName, payload.kind,
                    requireNotNull(VoiceMoneyRules.parseMinor(payload.amount)), payload.currency, payload.source,
                    payload.comment, payload.personId, payload.eventId, payload.commandId,
                    payload.recordedAt.toString(), payload.occurredAt.toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, when (event.kind) { "receipt" -> "receipt_recorded"; "debt" -> "debt_recorded"; else -> "expense_recorded" },
                    mapOf("id" to event.eventId, "person_name" to payload.personName, "minor_units" to event.minorUnits,
                        "currency" to event.currency, "occurred_at" to event.occurredAt, "comment" to event.comment))
            } catch (error: Exception) {
                when (error.message) {
                    "person_name_ambiguous" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "person_name_ambiguous")
                    "idempotency_conflict", "money_event_id_conflict" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "duplicate_conflict")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_store_unavailable")
                }
            }
        }
    }

    private inner class SettleDebt : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("settle_debt", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val obligationId = call.arguments["obligation_id"] as? String ?: return ToolPreparation.Rejected("invalid_obligation")
            val amount = moneyAmountArgument(call.arguments["amount"]) ?: return ToolPreparation.Rejected("invalid_amount")
            val minor = VoiceMoneyRules.parseMinor(amount) ?: return ToolPreparation.Rejected("invalid_amount")
            val debt = repository.moneyEvent(turn.scopeId, obligationId)?.takeIf { it.kind == "debt" }
                ?: return ToolPreparation.Rejected("obligation_not_found")
            val date = (call.arguments["occurred_date"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if ((call.arguments["occurred_date"] as? String)?.isNotBlank() == true && date == null)
                return ToolPreparation.Rejected("invalid_date")
            val now = Instant.now()
            val occurredAt = (date ?: now.atZone(ZoneId.of("Asia/Baku")).toLocalDate())
                .atStartOfDay(ZoneId.of("Asia/Baku")).toInstant()
            val reason = (call.arguments["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Repayment"
            if (reason.length > 500) return ToolPreparation.Rejected("invalid_reason")
            val paymentEventId = (call.arguments["payment_event_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ToolPreparation.Rejected("payment_event_required")
            if (paymentEventId.length > 100) return ToolPreparation.Rejected("invalid_payment_event")
            val payment = repository.moneyEvent(turn.scopeId, paymentEventId)?.takeIf { it.kind == "receipt" }
                ?: return ToolPreparation.Rejected("payment_event_not_found")
            if (payment.personId != debt.personId) return ToolPreparation.Conflict("payment_obligation_mismatch")
            if (payment.currency != debt.currency) return ToolPreparation.Conflict("currency_mismatch")
            if (repository.isFinanceEventVoided(turn.scopeId, paymentEventId)) return ToolPreparation.Conflict("payment_event_voided")
            if (minor > payment.minorUnits - repository.appliedToDebtMinor(turn.scopeId, paymentEventId))
                return ToolPreparation.Conflict("payment_amount_already_applied")
            val remaining = try { repository.outstandingDebtMinor(turn.scopeId, obligationId) }
                catch (_: Exception) { return ToolPreparation.Rejected("obligation_unavailable") }
            if (remaining <= 0 || minor > remaining) return ToolPreparation.Conflict("settlement_exceeds_balance")
            val digest = sha256("settlement\u0000${turn.scopeId}\u0000$obligationId\u0000$minor\u0000${debt.currency}\u0000$occurredAt\u0000$reason\u0000${paymentEventId.orEmpty()}\u0000${turn.text}")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(obligationId to debt.minorUnits), false,
                SettlementPayload(turn.scopeId, obligationId, minor, debt.currency, occurredAt.toString(), reason, commandId, paymentEventId)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? SettlementPayload ?: return rejected()
            return try {
                val remaining = repository.recordSettlement(payload.scope, payload.commandId, payload.commandId,
                    payload.obligationId, payload.minorUnits, payload.currency, payload.occurredAt, payload.reason, payload.paymentEventId)
                ToolCommitResult(ToolObservationStatus.APPLIED, "debt_settlement_recorded", mapOf(
                    "obligation_id" to payload.obligationId, "settled_minor_units" to payload.minorUnits,
                    "payment_event_id" to payload.paymentEventId, "currency" to payload.currency,
                    "remaining_minor_units" to remaining, "occurred_at" to payload.occurredAt))
            } catch (error: Exception) {
                when (error.message) {
                    "settlement_exceeds_balance", "payment_obligation_mismatch", "payment_event_voided", "payment_amount_already_applied" ->
                        ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    "obligation_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "obligation_not_found")
                    "payment_event_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "payment_event_not_found")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "settlement_failed")
                }
            }
        }
    }

    private inner class ReverseMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("reverse_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val reason = (call.arguments["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "User-requested correction"
            val issue = repository.canReverseFinanceEvent(turn.scopeId, eventId)
            if (issue != null) return ToolPreparation.Conflict(issue)
            val event = repository.moneyEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("event_not_found")
            val digest = sha256("money_void\u0000${turn.scopeId}\u0000$eventId\u0000$reason")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, mapOf(eventId to 1L), false,
                ReverseFinancePayload(turn.scopeId, eventId, commandId, reason)))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? ReverseFinancePayload ?: return rejected()
            return try {
                val result = repository.reverseFinanceEvent(payload.scope, payload.eventId, payload.commandId,
                    payload.reason, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "financial_event_reversed", mapOf(
                    "event_id" to result.event.eventId, "kind" to result.event.kind, "minor_units" to result.event.minorUnits,
                    "currency" to result.event.currency, "reversed" to true,
                    "reversed_debt_allocations" to result.reversedAllocations.map { mapOf(
                        "obligation_id" to it.obligationId, "minor_units" to it.minorUnits) },
                    "replacement_required_if_correcting" to true))
            } catch (error: Exception) {
                when (error.message) {
                    "event_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "event_not_found")
                    "event_already_reversed", "event_has_settlements" -> ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "financial_event_reverse_failed")
                }
            }
        }
    }

    private inner class CorrectMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("correct_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            val amount = moneyAmountArgument(call.arguments["new_amount"]) ?: return ToolPreparation.Rejected("invalid_amount_type")
            val minorUnits = VoiceMoneyRules.parseMinor(amount) ?: return ToolPreparation.Rejected("invalid_amount_format")
            val event = repository.moneyEvent(turn.scopeId, eventId) ?: return ToolPreparation.Rejected("event_not_found")
            if (event.currency != "AZN") return ToolPreparation.Rejected("unsupported_currency")
            if (event.minorUnits == minorUnits) return ToolPreparation.Rejected("amount_unchanged")
            val reason = (call.arguments["reason"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "User-requested amount correction"
            if (reason.length > 500) return ToolPreparation.Rejected("invalid_reason")
            val digest = sha256("finance_correction\u0000${turn.scopeId}\u0000$eventId\u0000$minorUnits\u0000$reason")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                FinanceCorrectionPayload(turn.scopeId, eventId, minorUnits, commandId, reason)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? FinanceCorrectionPayload ?: return rejected()
            return try {
                val result = repository.correctFinanceEventAmount(payload.scope, payload.eventId, payload.newMinorUnits,
                    payload.commandId, payload.reason, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "money_event_corrected", mapOf(
                    "event_id" to result.event.eventId,
                    "kind" to result.event.kind,
                    "new_minor_units" to result.event.minorUnits,
                    "currency" to result.event.currency,
                    "adjusted_allocation_count" to result.adjustedAllocations.size,
                    "remaining_debt_minor_units" to result.remainingDebtMinorByEvent,
                ))
            } catch (error: Exception) {
                when (error.message) {
                    "event_not_found" -> ToolCommitResult(ToolObservationStatus.REJECTED, "event_not_found")
                    "event_already_reversed" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "event_already_reversed")
                    "correction_below_settled_amount", "correction_requires_allocation_review", "settlement_exceeds_balance",
                    "currency_mismatch", "amount_unchanged" -> ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_correction_failed")
                }
            }
        }
    }

    private inner class AnnotateMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("annotate_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeEnabled)

        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val eventId = call.arguments["event_id"] as? String ?: return ToolPreparation.Rejected("invalid_event_id")
            if (repository.moneyEvent(turn.scopeId, eventId) == null) return ToolPreparation.Rejected("event_not_found")
            if (repository.isFinanceEventVoided(turn.scopeId, eventId)) return ToolPreparation.Rejected("event_already_reversed")
            val annotation = turn.text.trim()
            if (annotation.isBlank() || annotation.length > 500) return ToolPreparation.Rejected("invalid_annotation")
            val digest = sha256("finance_annotation\u0000${turn.scopeId}\u0000$eventId\u0000$annotation")
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false,
                FinanceAnnotationPayload(turn.scopeId, eventId, commandId, annotation)))
        }

        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult {
            val payload = command.opaquePayload as? FinanceAnnotationPayload ?: return rejected()
            return try {
                val row = repository.annotateFinanceEvent(payload.scope, payload.eventId, payload.commandId,
                    payload.annotation, Instant.now().toString())
                ToolCommitResult(ToolObservationStatus.APPLIED, "money_event_annotated", mapOf(
                    "event_id" to payload.eventId, "annotation" to row.reason))
            } catch (error: Exception) {
                when (error.message) {
                    "event_not_found", "event_already_reversed" -> ToolCommitResult(ToolObservationStatus.CONFLICT, error.message!!)
                    "idempotency_conflict" -> ToolCommitResult(ToolObservationStatus.CONFLICT, "duplicate_conflict")
                    else -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_annotation_failed")
                }
            }
        }
    }

    private data class SavePayload(val scope: String, val objectId: String,
        val kind: DomainKind, val title: String, val body: String)
    private data class EditObjectPayload(val scope: String, val id: String, val version: Long, val title: String,
        val body: String, val status: String)
    private data class DeleteObjectPayload(val scope: String, val preview: ai.memora.app.data.protectedstore.DeletePreview)
    private data class RestoreObjectPayload(val scope: String, val id: String, val version: Long)
    private data class CreateTablePayload(val scope: String, val id: String, val title: String, val columns: List<ColumnSpec>)
    private data class AddRowPayload(val scope: String, val tableId: String, val schemaVersion: Long,
        val rowId: String, val values: Map<String, Any?>)
    private data class UpdateRowPayload(val scope: String, val tableId: String, val rowId: String,
        val rowVersion: Long, val schemaVersion: Long, val values: Map<String, Any?>)
    private data class DeleteRowPayload(val scope: String, val tableId: String, val rowId: String, val version: Long)
    private data class RenameTablePayload(val scope: String, val tableId: String, val version: Long, val title: String)
    private data class TableLifecyclePayload(val scope: String, val tableId: String, val version: Long)
    private data class CreateTaskPayload(val taskId: String, val scope: String, val actor: String,
        val commandId: String, val title: String, val dueLocal: String?, val dueZone: String?, val remind: Boolean,
        val person: String?, val project: String?)
    private data class LinkPayload(val scope: String, val id: String, val from: String, val to: String, val relation: String)
    private data class CompletionPayload(val id: String, val scope: String, val actor: String, val commandId: String,
        val version: Long, val completed: Boolean)
    private data class CalendarPayload(val input: CalendarInput)
    private data class CalendarEditPayload(val input: CalendarInput, val expectedVersion: Long)
    private data class CalendarCancelPayload(val scope: String, val eventId: String, val expectedVersion: Long)
    private data class FinancePayload(val scope: String, val personName: String, val kind: String, val amount: String,
        val currency: String, val source: String, val comment: String?, val personId: String, val eventId: String,
        val commandId: String, val recordedAt: Instant, val occurredAt: Instant)
    private data class SettlementPayload(val scope: String, val obligationId: String, val minorUnits: Long,
        val currency: String, val occurredAt: String, val reason: String, val commandId: String, val paymentEventId: String?)
    private data class ReverseFinancePayload(val scope: String, val eventId: String, val commandId: String, val reason: String)
    private data class FinanceCorrectionPayload(val scope: String, val eventId: String, val newMinorUnits: Long,
        val commandId: String, val reason: String)
    private data class FinanceAnnotationPayload(val scope: String, val eventId: String, val commandId: String,
        val annotation: String)
    private data class TaskEditPayload(val event: TaskEvent)

    private data class ParsedCell(val valid: Boolean, val value: Any?)

    private fun parseTableCell(column: TableColumnRow, raw: Any?): ParsedCell {
        if (raw == JSONObject.NULL) return ParsedCell(true, null)
        return try {
            when (ColumnType.valueOf(column.valueType)) {
                ColumnType.TEXT, ColumnType.PERSON_REF, ColumnType.PROJECT_REF -> when (raw) {
                    is String -> ParsedCell(true, raw)
                    is Number, is Boolean -> ParsedCell(true, raw.toString())
                    else -> ParsedCell(false, null)
                }
                ColumnType.INTEGER -> when (raw) {
                    is Int, is Long -> ParsedCell(true, (raw as Number).toLong())
                    is String -> raw.toLongOrNull()?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                    else -> ParsedCell(false, null)
                }
                ColumnType.DECIMAL -> (raw as? String ?: (raw as? Number)?.toString())
                    ?.takeIf { runCatching { java.math.BigDecimal(it); true }.getOrDefault(false) }
                    ?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                ColumnType.BOOLEAN -> when (raw) {
                    is Boolean -> ParsedCell(true, raw)
                    is String -> raw.toBooleanStrictOrNull()?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                    else -> ParsedCell(false, null)
                }
                ColumnType.DATE -> (raw as? String)?.takeIf { runCatching { LocalDate.parse(it); true }.getOrDefault(false) }
                    ?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                ColumnType.DATETIME -> (raw as? String)?.takeIf { runCatching { java.time.OffsetDateTime.parse(it); true }.getOrDefault(false) }
                    ?.let { ParsedCell(true, it) } ?: ParsedCell(false, null)
                ColumnType.MONEY -> {
                    val values = when (raw) {
                        is JSONObject -> mapOf("minorUnits" to raw.getLong("minorUnits"), "currency" to raw.getString("currency"))
                        is Map<*, *> -> raw
                        is String -> {
                            val match = Regex("^\\s*([0-9]{1,14}(?:[.,][0-9]{1,2})?)(?:\\s+([A-Za-z]{3}))?\\s*$").matchEntire(raw)
                                ?: return ParsedCell(false, null)
                            val minor = java.math.BigDecimal(match.groupValues[1].replace(',', '.')).movePointRight(2).longValueExact()
                            mapOf("minorUnits" to minor, "currency" to match.groupValues[2].ifBlank { "AZN" }.uppercase())
                        }
                        else -> return ParsedCell(false, null)
                    }
                    val minor = values["minorUnits"] as? Number ?: return ParsedCell(false, null)
                    val currency = values["currency"] as? String ?: return ParsedCell(false, null)
                    if (minor.toLong() < 0 || runCatching { java.util.Currency.getInstance(currency) }.isFailure) ParsedCell(false, null)
                    else ParsedCell(true, mapOf("minorUnits" to minor.toLong(), "currency" to currency))
                }
            }
        } catch (_: Exception) { ParsedCell(false, null) }
    }

    private fun rejected() = ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_arguments")
    private fun moneyAmountArgument(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.length <= 32 }
        is Number -> runCatching {
            BigDecimal(value.toString()).stripTrailingZeros().toPlainString().takeIf { it.length <= 32 }
        }.getOrNull()
        else -> null
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

package ai.memora.app.agent

import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.ProtectedMemoryRepository
import ai.memora.app.domain.TaskEvent
import ai.memora.app.data.VoiceMoneyStore
import ai.memora.app.data.VoiceStoreRead
import ai.memora.app.data.VoiceStoreWrite
import ai.memora.app.domain.VoiceMoneyRules
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** First production tool slice: bounded evidence search and explicit local memory writes. */
internal class LocalMemoryTools(private val repository: ProtectedDomainRepository, private val memory: ProtectedMemoryRepository,
    private val financeStore: VoiceMoneyStore? = null) {
    fun handlers(): List<AgentToolHandler> = listOf(
        SearchMemory(), SaveNote(), EditMemoryObject(), DeleteMemoryObject(), RestoreMemoryObject(), LinkObjects(), ListTables(), ReadTableRows(), CreateTable(), AddTableRow(),
        UpdateTableRow(), DeleteTableRow(), RestoreTableRow(), RenameTable(), DeleteTable(), RestoreTable(),
        ListTasks(), CreateTask(), SetTaskCompletion(), ListCalendarEvents(), CreateCalendarEvent(),
        SearchMoney(), RecordMoneyEvent(),
    )

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
            return ToolCommitResult(ToolObservationStatus.APPLIED, "tables_loaded", mapOf("tables" to tables.map { item ->
                mapOf("id" to item.table.tableId, "title" to item.table.title, "schema_version" to item.table.schemaVersion,
                    "columns" to item.columns.map { mapOf("id" to it.columnId, "name" to it.name, "type" to it.valueType) },
                    "rows" to item.rows.take(30).map { it.valuesJson })
            }))
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
            val rows = table.rows.asSequence().filter { query.isBlank() || it.valuesJson.contains(query, ignoreCase = true) }
                .take(40).map { mapOf("id" to it.rowId, "version" to it.version, "values" to it.valuesJson.take(4000)) }.toList()
            return ToolCommitResult(ToolObservationStatus.APPLIED, "table_rows_loaded", mapOf(
                "table_id" to tableId, "title" to table.table.title, "columns" to table.columns.map { mapOf("id" to it.columnId, "name" to it.name, "type" to it.valueType) },
                "rows" to rows, "returned" to rows.size,
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
                when (value) {
                    JSONObject.NULL -> merged.put(column.columnId, JSONObject.NULL)
                    is String, is Number, is Boolean -> merged.put(column.columnId, value.toString())
                    else -> return ToolPreparation.Rejected("unsupported_cell_value")
                }
            }
            val values = merged.keys().asSequence().associateWith { merged.opt(it)?.takeUnless { v -> v == JSONObject.NULL }?.toString() }
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
                ToolCommitResult(ToolObservationStatus.APPLIED, "task_completion_changed", mapOf(
                    "id" to task.id, "title" to task.title, "completed" to task.completed, "version" to task.version))
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
                val events = repository.calendarBetween(turn.scopeId, from, until).take(100)
                ToolCommitResult(ToolObservationStatus.APPLIED, "calendar_loaded", mapOf("events" to events.map { event ->
                    mapOf("id" to event.eventId, "title" to event.title, "start_local" to event.startLocal,
                        "end_local" to event.endLocal, "zone_id" to event.zoneId, "precision" to event.timePrecision)
                }))
            } catch (_: Exception) { ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_calendar_range") }
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

    private inner class SearchMoney : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("search_money", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.READ, runtimeEnabled = financeStore != null)
        override suspend fun read(call: AgentModelToolCall, turn: AgentTurnRequest): ToolCommitResult {
            val name = call.arguments["person_name"] as? String ?: return rejected()
            if (name.isBlank() || name.length > 100) return rejected()
            val snapshot = when (val read = financeStore?.load()) {
                is VoiceStoreRead.Ready -> read.snapshot
                else -> return ToolCommitResult(ToolObservationStatus.FAILED, "finance_store_unavailable")
            }
            val matches = snapshot.persons.filter { VoiceMoneyRules.key(it.name) == VoiceMoneyRules.key(name) }
            if (matches.size > 1) return ToolCommitResult(ToolObservationStatus.CONFLICT, "person_name_ambiguous")
            val person = matches.singleOrNull() ?: return ToolCommitResult(ToolObservationStatus.APPLIED, "no_financial_records",
                mapOf("person_found" to false, "receipts" to emptyList<Any>(), "debts" to emptyList<Any>()))
            val receipts = snapshot.receipts.filter { it.personId == person.id }.take(50)
            val debts = snapshot.debts.filter { it.personId == person.id }.take(50)
            return ToolCommitResult(ToolObservationStatus.APPLIED, "financial_evidence_loaded", mapOf(
                "person_found" to true, "person_name" to person.name,
                "receipts" to receipts.map { mapOf("minor_units" to it.minorUnits, "currency" to it.currency,
                    "recorded_at" to it.recordedAt.toString(), "occurred_at" to it.occurredAt?.toString(),
                    "comment" to it.comment, "source" to it.source.take(1000)) },
                "debts" to debts.map { mapOf("minor_units" to it.minorUnits, "currency" to it.currency,
                    "recorded_at" to it.recordedAt.toString(), "occurred_at" to it.occurredAt?.toString(),
                    "comment" to it.comment, "source" to it.source.take(1000)) },
            ))
        }
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String) = ToolPreparation.Rejected("read_only_tool")
        override suspend fun commit(command: PreparedAgentCommand) = rejected()
    }

    private inner class RecordMoneyEvent : AgentToolHandler {
        override val descriptor = AgentToolDescriptor("record_money_event", 1, ToolAccess.PERSISTED_MEMORY,
            ToolEffect.WRITE, runtimeEnabled = financeStore != null)
        override suspend fun prepare(call: AgentModelToolCall, turn: AgentTurnRequest, commandId: String): ToolPreparation {
            val store = financeStore ?: return ToolPreparation.Rejected("finance_tool_unavailable")
            val kind = call.arguments["kind"] as? String ?: return ToolPreparation.Rejected("invalid_kind")
            val personName = call.arguments["person_name"] as? String ?: return ToolPreparation.Rejected("invalid_person")
            val amount = call.arguments["amount"] as? String ?: return ToolPreparation.Rejected("invalid_amount")
            val currency = (call.arguments["currency"] as? String)?.uppercase() ?: "AZN"
            val comment = (call.arguments["comment"] as? String)?.takeIf { it.isNotBlank() }
            val localDate = (call.arguments["occurred_date"] as? String)?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (personName.isBlank() || personName.length > 100 || amount.length > 32 || VoiceMoneyRules.parseMinor(amount) == null ||
                currency != "AZN" || (call.arguments["occurred_date"] as? String)?.isNotBlank() == true && localDate == null ||
                (comment?.length ?: 0) > 1000)
                return ToolPreparation.Rejected("invalid_money_event")
            val snapshot = (store.load() as? VoiceStoreRead.Ready)?.snapshot
                ?: return ToolPreparation.Rejected("finance_store_unavailable")
            val personId = UUID.randomUUID().toString()
            val eventId = UUID.randomUUID().toString()
            val recordedAt = Instant.now()
            val occurredAt = (localDate ?: recordedAt.atZone(ZoneId.of("Asia/Baku")).toLocalDate())
                .atStartOfDay(ZoneId.of("Asia/Baku")).toInstant()
            val digest = sha256("money\u0000${turn.scopeId}\u0000$kind\u0000${VoiceMoneyRules.key(personName)}\u0000$amount\u0000$currency\u0000$occurredAt\u0000${comment.orEmpty()}\u0000${turn.text}")
            val payload: Any = when (kind) {
                "receipt" -> {
                    if (snapshot.persons.count { VoiceMoneyRules.key(it.name) == VoiceMoneyRules.key(personName) } > 1)
                        return ToolPreparation.Conflict("person_name_ambiguous")
                    AgentReceiptPayload(store, personName.trim(), amount, currency, turn.text, comment,
                        personId, eventId, commandId, recordedAt, occurredAt)
                }
                "debt" -> {
                    if (snapshot.persons.count { VoiceMoneyRules.key(it.name) == VoiceMoneyRules.key(personName) } > 1)
                        return ToolPreparation.Conflict("person_name_ambiguous")
                    AgentDebtPayload(store, personName.trim(), amount, currency, turn.text, comment,
                        personId, eventId, commandId, recordedAt, occurredAt)
                }
                else -> return ToolPreparation.Rejected("invalid_kind")
            }
            return ToolPreparation.Prepared(PreparedAgentCommand(commandId, digest, emptyMap(), false, payload))
        }
        override suspend fun commit(command: PreparedAgentCommand): ToolCommitResult = when (val payload = command.opaquePayload) {
            is AgentReceiptPayload -> writeResult(payload.store.recordReceipt(payload.personName, payload.amount, payload.currency,
                payload.source, payload.comment, payload.personId, payload.eventId, payload.commandId, payload.recordedAt,
                payload.occurredAt), "receipt_recorded", payload.eventId, payload.personName, VoiceMoneyRules.parseMinor(payload.amount))
            is AgentDebtPayload -> writeResult(payload.store.recordDebt(payload.personName, payload.amount, payload.currency,
                payload.source, payload.comment, payload.personId, payload.eventId, payload.commandId, payload.recordedAt,
                payload.occurredAt), "debt_recorded", payload.eventId, payload.personName, VoiceMoneyRules.parseMinor(payload.amount))
            else -> rejected()
        }
        private fun writeResult(result: VoiceStoreWrite, code: String, id: String, personName: String, minor: Long?) = when (result) {
            VoiceStoreWrite.Saved, VoiceStoreWrite.Duplicate -> ToolCommitResult(ToolObservationStatus.APPLIED, code,
                mapOf("id" to id, "person_name" to personName, "minor_units" to (minor ?: 0L), "currency" to "AZN"))
            VoiceStoreWrite.Ambiguous -> ToolCommitResult(ToolObservationStatus.CONFLICT, "person_name_ambiguous")
            VoiceStoreWrite.Conflict -> ToolCommitResult(ToolObservationStatus.CONFLICT, "duplicate_conflict")
            VoiceStoreWrite.Invalid -> ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_money_event")
            VoiceStoreWrite.Unavailable -> ToolCommitResult(ToolObservationStatus.FAILED, "finance_store_unavailable")
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
        val commandId: String, val title: String)
    private data class LinkPayload(val scope: String, val id: String, val from: String, val to: String, val relation: String)
    private data class CompletionPayload(val id: String, val scope: String, val actor: String, val commandId: String,
        val version: Long, val completed: Boolean)
    private data class CalendarPayload(val input: CalendarInput)
    private data class AgentReceiptPayload(val store: VoiceMoneyStore, val personName: String, val amount: String,
        val currency: String, val source: String, val comment: String?, val personId: String, val eventId: String,
        val commandId: String, val recordedAt: Instant, val occurredAt: Instant)
    private data class AgentDebtPayload(val store: VoiceMoneyStore, val personName: String, val amount: String,
        val currency: String, val source: String, val comment: String?, val personId: String, val eventId: String,
        val commandId: String, val recordedAt: Instant, val occurredAt: Instant)

    private fun rejected() = ToolCommitResult(ToolObservationStatus.REJECTED, "invalid_arguments")
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

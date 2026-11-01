package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Currency
import java.util.Locale
import java.security.MessageDigest
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskApply
import ai.memora.app.domain.TaskDecision
import ai.memora.app.domain.TaskEvent
import ai.memora.app.domain.TaskRules
import org.json.JSONArray
import org.json.JSONObject

internal enum class DomainKind { PERSON, PROJECT, NOTE }
internal enum class ColumnType { TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, MONEY, PERSON_REF, PROJECT_REF }
internal data class ColumnSpec(val id: String, val name: String, val type: ColumnType, val required: Boolean = false, val config: JSONObject = JSONObject())
internal data class DomainHit(val objectId: String, val kind: DomainKind, val title: String, val version: Long,
    val sourceRevisionId: String?, val evidenceRevisionId: String, val evidenceStartUtf16: Int,
    val evidenceEndUtf16: Int, val evidenceText: String)
internal data class DeletePreview(val objectId: String, val expectedVersion: Long, val linkedIds: List<String>)
internal data class ImportPreview(val tableId: String, val schemaVersion: Long, val rows: List<Pair<String, Map<String, Any?>>>)
internal data class TableSnapshot(val table: UserTableRow, val columns: List<TableColumnRow>, val rows: List<TableDataRow>)
internal data class CalendarInput(val eventId: String, val scopeId: String, val title: String, val startLocal: String, val endLocal: String?, val zoneId: String, val precision: String)

/** Protected local domain port. This remains disconnected from application workflows until P1 gates pass. */
internal class ProtectedDomainRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()

    suspend fun tables(scope: String): List<TableSnapshot> = dao.activeUserTables(scope).map { table ->
        TableSnapshot(table, dao.columns(table.tableId), dao.rows(table.tableId, scope))
    }

    suspend fun createObject(scope: String, id: String, kind: DomainKind, title: String, body: String?,
        sourceRevisionId: String?, now: String): DomainObjectRow = db.withTransaction {
        require(scope.isNotBlank() && id.isNotBlank()); requireTitle(title)
        require(body == null || body.length <= 100_000)
        val row = DomainObjectRow(id, scope, kind.name, title, body, "active", 1, null)
        dao.insertDomainObject(row)
        dao.insertDomainRevision(DomainRevisionRow(id, 1, payload(title, body, "active", null), sourceRevisionId, now))
        row
    }

    suspend fun updateObject(scope: String, id: String, expectedVersion: Long, title: String, body: String?,
        status: String, sourceRevisionId: String?, now: String): DomainObjectRow = db.withTransaction {
        val current = dao.domainObject(scope, id) ?: error("not_found")
        check(current.version == expectedVersion && current.deletedAt == null) { "version_conflict" }
        requireTitle(title); require(body == null || body.length <= 100_000)
        require(status in setOf("active", "archived")); check(expectedVersion < Long.MAX_VALUE)
        val next = expectedVersion + 1
        check(dao.updateDomainObject(scope, id, expectedVersion, next, title, body, status) == 1) { "version_conflict" }
        dao.insertDomainRevision(DomainRevisionRow(id, next, payload(title, body, status, null), sourceRevisionId, now))
        requireNotNull(dao.domainObject(scope, id))
    }

    suspend fun link(scope: String, linkId: String, fromId: String, toId: String, relation: String) = db.withTransaction {
        require(fromId != toId && relation.matches(Regex("[a-z][a-z0-9_]{0,39}")))
        val from = dao.domainObject(scope, fromId); val to = dao.domainObject(scope, toId)
        check(from?.deletedAt == null && to?.deletedAt == null) { "not_found" }
        check(dao.activeLinks(scope).none { it.fromId == fromId && it.toId == toId && it.relation == relation }) { "duplicate_link" }
        dao.insertDomainLink(DomainLinkRow(linkId, scope, fromId, toId, relation, 1, null))
    }

    suspend fun previewDelete(scope: String, id: String, expectedVersion: Long): DeletePreview {
        val row = dao.domainObject(scope, id) ?: error("not_found")
        check(row.deletedAt == null && row.version == expectedVersion) { "version_conflict" }
        return DeletePreview(id, expectedVersion, dao.activeLinks(scope).filter { it.fromId == id || it.toId == id }
            .flatMap { listOf(it.fromId, it.toId) }.filter { it != id }.distinct().sorted())
    }

    suspend fun delete(scope: String, preview: DeletePreview, now: String) = db.withTransaction {
        check(preview == previewDelete(scope, preview.objectId, preview.expectedVersion)) { "preview_changed" }
        check(preview.expectedVersion < Long.MAX_VALUE)
        check(dao.tombstoneDomainObject(scope, preview.objectId, preview.expectedVersion, preview.expectedVersion + 1, now) == 1)
        val row = requireNotNull(dao.domainObject(scope, preview.objectId))
        val sourceRevisionId = dao.domainRevisions(row.objectId).lastOrNull()?.sourceRevisionId
        dao.insertDomainRevision(DomainRevisionRow(row.objectId, row.version, payload(row.title, row.body, row.status, row.deletedAt), sourceRevisionId, now))
    }

    suspend fun restore(scope: String, id: String, expectedVersion: Long, now: String): DomainObjectRow = db.withTransaction {
        val current = dao.domainObject(scope, id) ?: error("not_found")
        check(current.deletedAt != null && current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(dao.restoreDomainObject(scope, id, expectedVersion, expectedVersion + 1) == 1)
        val restored = requireNotNull(dao.domainObject(scope, id))
        val sourceRevisionId = dao.domainRevisions(id).lastOrNull()?.sourceRevisionId
        dao.insertDomainRevision(DomainRevisionRow(id, restored.version, payload(restored.title, restored.body, restored.status, null), sourceRevisionId, now))
        restored
    }

    suspend fun createTable(scope: String, id: String, title: String, mode: String, schema: List<ColumnSpec>, now: String = java.time.Instant.now().toString()) = db.withTransaction {
        requireTitle(title); require(mode in setOf("editable", "linked_view")); validateSchema(schema)
        dao.insertUserTable(UserTableRow(id, scope, title, mode, 1, 1, null))
        val columns = schema.mapIndexed { index, col -> TableColumnRow(id, col.id, index, col.name, col.type.name, col.required, col.config.toString()) }
        dao.insertColumns(columns)
        dao.insertSchemaRevision(TableSchemaRevisionRow(id, 1, columnsJson(columns), now))
    }

    /** Safe schema edits preserve every existing column ID/type and only append optional columns. */
    suspend fun changeTableSchema(scope: String, tableId: String, expectedSchemaVersion: Long,
        schema: List<ColumnSpec>, now: String): Long = db.withTransaction {
        validateSchema(schema)
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null && table.schemaVersion == expectedSchemaVersion) { "schema_conflict" }
        check(expectedSchemaVersion < Long.MAX_VALUE)
        val old = dao.columns(tableId).associateBy { it.columnId }
        val requested = schema.associateBy { it.id }
        require(old.keys.all { it in requested }) { "destructive_schema_change_requires_preview" }
        old.forEach { (id, previous) ->
            val next = requested.getValue(id)
            require(previous.valueType == next.type.name && previous.required == next.required && previous.configJson == next.config.toString()) {
                "type_change_requires_preview"
            }
        }
        schema.filter { it.id !in old }.forEach { require(!it.required) { "new_required_column_requires_default" } }
        check(dao.updateTableSchemaVersion(scope, tableId, expectedSchemaVersion, expectedSchemaVersion + 1) == 1) { "schema_conflict" }
        val updated = schema.mapIndexed { index, col -> TableColumnRow(tableId, col.id, index, col.name, col.type.name, col.required, col.config.toString()) }
        dao.updateColumns(updated.filter { it.columnId in old })
        dao.insertColumns(updated.filter { it.columnId !in old })
        dao.insertSchemaRevision(TableSchemaRevisionRow(tableId, expectedSchemaVersion + 1, columnsJson(updated), now))
        expectedSchemaVersion + 1
    }

    suspend fun addRows(scope: String, tableId: String, expectedSchemaVersion: Long, rowValues: List<Pair<String, Map<String, Any?>>>) = db.withTransaction {
        require(rowValues.size in 1..100)
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null && table.schemaVersion == expectedSchemaVersion) { "schema_conflict" }
        check(table.mode == "editable") { "linked_view_read_only" }
        val columns = dao.columns(tableId).map { it.columnId to ColumnSpec(it.columnId, it.name, ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) }.toMap()
        val ids = hashSetOf<String>()
        val existingRows = dao.rows(tableId, scope).associateBy { it.rowId }
        val prepared = rowValues.map { (id, values) ->
            require(id.isNotBlank() && ids.add(id)); validateRow(columns, values)
            val encoded = encodeValues(values)
            existingRows[id]?.let { existing -> check(existing.valuesJson == encoded) { "idempotency_conflict" } }
            existingRows[id] ?: TableDataRow(id, tableId, scope, 1, encoded, null)
        }
        val missing = prepared.filter { it.rowId !in existingRows }
        missing.forEach { row -> check(dao.rowById(row.rowId) == null) { "row_id_exists_in_another_table" } }
        missing.forEach { dao.insertTableRows(listOf(it)) }
        prepared
    }

    suspend fun updateRow(scope: String, tableId: String, rowId: String, expectedVersion: Long,
        expectedSchemaVersion: Long, values: Map<String, Any?>): TableDataRow = db.withTransaction {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.schemaVersion == expectedSchemaVersion && table.deletedAt == null) { "schema_conflict" }
        check(table.mode == "editable") { "linked_view_read_only" }
        val existing = dao.rows(tableId, scope).firstOrNull { it.rowId == rowId } ?: error("not_found")
        check(existing.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        val columns = dao.columns(tableId).map { it.columnId to ColumnSpec(it.columnId, it.name, ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) }.toMap()
        validateRow(columns, values)
        val encoded = encodeValues(values)
        check(dao.updateTableRow(rowId, tableId, scope, expectedVersion, expectedVersion + 1, encoded) == 1)
        existing.copy(version = expectedVersion + 1, valuesJson = encoded)
    }

    /** Exact money aggregate; results stay partitioned by currency and never use floating point. */
    suspend fun aggregateMoney(scope: String, tableId: String, columnId: String): Map<String, Long> {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null)
        check(dao.columns(tableId).any { it.columnId == columnId && it.valueType == ColumnType.MONEY.name }) { "not_money_column" }
        val totals = sortedMapOf<String, Long>()
        for (row in dao.rows(tableId, scope)) {
            val values = JSONObject(row.valuesJson)
            if (!values.has(columnId) || values.isNull(columnId)) continue
            val money = values.getJSONObject(columnId)
            val amount = money.getLong("minorUnits"); val currency = money.getString("currency")
            totals[currency] = Math.addExact(totals[currency] ?: 0L, amount)
        }
        return totals
    }

    /** Append-only task command path; duplicate app command IDs are rejected atomically. */
    suspend fun appendTaskEvent(event: TaskEvent): Task = db.withTransaction {
        require(event.taskId.isNotBlank() && event.scopeId.isNotBlank() && event.actorId.isNotBlank() && event.commandId.isNotBlank())
        check(dao.taskEventsForCommand(event.commandId).isEmpty()) { "idempotency_conflict" }
        val current = dao.taskEvents().asSequence().filter { it.taskId == event.taskId && it.scopeId == event.scopeId }
            .fold<TaskEventRow, Task?>(null) { task, row ->
                val applied = TaskRules.apply(task, fromTaskRow(row)) as? TaskApply.Applied ?: error("task_history_invalid")
                applied.task
            }
        val applied = TaskRules.apply(current, event) as? TaskApply.Applied ?: error("task_conflict_or_invalid")
        dao.putTaskEvents(listOf(toTaskRow(event)))
        applied.task
    }

    suspend fun exportTable(scope: String, tableId: String): String {
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(table.deletedAt == null)
        val columns = dao.columns(tableId)
        val rows = dao.rows(tableId, scope)
        return JSONObject().put("format", "memora-table-v1").put("scope", scope).put("tableId", tableId)
            .put("schemaVersion", table.schemaVersion).put("columns", JSONArray(columns.map { JSONObject()
                .put("id", it.columnId).put("name", it.name).put("type", it.valueType).put("required", it.required).put("config", JSONObject(it.configJson)) }))
            .put("rows", JSONArray(rows.map { JSONObject().put("id", it.rowId).put("version", it.version).put("values", JSONObject(it.valuesJson)) })).toString()
    }

    suspend fun previewImport(scope: String, tableId: String, json: String): ImportPreview {
        require(json.length <= 1_000_000)
        val root = JSONObject(json); require(root.getString("format") == "memora-table-v1")
        require(root.getString("scope") == scope && root.getString("tableId") == tableId) { "scope_mismatch" }
        val table = dao.userTable(scope, tableId) ?: error("not_found")
        check(root.getLong("schemaVersion") == table.schemaVersion) { "schema_conflict" }
        val columns = dao.columns(tableId).map { it.columnId to ColumnSpec(it.columnId, it.name, ColumnType.valueOf(it.valueType), it.required, JSONObject(it.configJson)) }.toMap()
        val rawRows = root.getJSONArray("rows"); require(rawRows.length() <= 1000)
        val ids = hashSetOf<String>()
        val parsed = (0 until rawRows.length()).map { index ->
            val row = rawRows.getJSONObject(index); require(ids.add(row.getString("id")))
            val values = row.getJSONObject("values").let { obj -> obj.keys().asSequence().associateWith { key -> jsonValue(obj.get(key)) } }
            validateRow(columns, values)
            deterministicImportId(tableId, row.getString("id")) to values
        }
        return ImportPreview(tableId, table.schemaVersion, parsed)
    }

    suspend fun importRows(scope: String, preview: ImportPreview): List<TableDataRow> =
        addRows(scope, preview.tableId, preview.schemaVersion, preview.rows)

    suspend fun search(scope: String, query: String, kinds: Set<DomainKind> = DomainKind.entries.toSet(), limit: Int = 20): List<DomainHit> {
        require(limit in 1..100 && query.length <= 500)
        val needle = fold(query.trim()); if (needle.isEmpty()) return emptyList()
        val allowedKinds = kinds.map(DomainKind::name).toSet()
        val hits = mutableListOf<DomainHit>()
        for (row in dao.activeObjects(scope).filter { it.kind in allowedKinds }) {
            val rev = dao.domainRevisions(row.objectId).lastOrNull() ?: continue
            val p = JSONObject(rev.payloadJson); val title = p.optString("title", row.title); val body = p.optString("body").takeIf { it.isNotEmpty() }
            val text = listOfNotNull(title, body).joinToString(" ")
            val start = text.indexOf(query.trim(), ignoreCase = true)
            if (start >= 0) hits += DomainHit(row.objectId, DomainKind.valueOf(row.kind), title, row.version,
                rev.sourceRevisionId, "${row.objectId}:${rev.version}", start, start + query.trim().length, text.take(1000))
        }
        return hits.sortedBy { it.title.lowercase() }.take(limit)
    }

    suspend fun context(scope: String, query: String, maxChars: Int = 6000): List<DomainHit> {
        require(maxChars in 100..20_000)
        var used = 0
        return search(scope, query, limit = 100).takeWhile { hit -> (used + hit.evidenceText.length).let { fits -> if (fits <= maxChars) used = fits; fits <= maxChars } }
    }

    suspend fun recordSettlement(scope: String, eventId: String, commandId: String, obligationId: String, minorUnits: Long,
        currency: String, occurredAt: String?, reason: String): Long = db.withTransaction {
        require(scope.isNotBlank() && minorUnits > 0 && reason.isNotBlank() && reason.length <= 500)
        val existing = dao.adjustmentForCommand(commandId)
        if (existing != null) {
            check(existing.scopeId == scope && existing.eventId == eventId && existing.obligationId == obligationId && existing.kind == "settlement" &&
                existing.minorUnits == minorUnits && existing.currency == currency && existing.occurredAt == occurredAt && existing.reason == reason) { "idempotency_conflict" }
            val obligation = dao.moneyEvent(scope, obligationId)?.takeIf { it.kind == "debt" } ?: error("obligation_not_found")
            val settled = dao.adjustments(scope, obligationId).fold(0L) { total, item -> when (item.kind) {
                "settlement", "correction" -> Math.addExact(total, item.minorUnits)
                "reversal" -> Math.subtractExact(total, item.minorUnits)
                else -> error("invalid_ledger_event")
            } }
            return@withTransaction Math.subtractExact(obligation.minorUnits, settled)
        }
        val obligation = dao.moneyEvent(scope, obligationId)?.takeIf { it.kind == "debt" } ?: error("obligation_not_found")
        require(currency == obligation.currency && isCurrency(currency)) { "currency_mismatch" }
        val prior = dao.adjustments(scope, obligationId)
        val net = prior.fold(0L) { total, item -> when (item.kind) {
            "settlement" -> Math.addExact(total, item.minorUnits)
            "correction" -> Math.addExact(total, item.minorUnits)
            "reversal" -> Math.subtractExact(total, item.minorUnits)
            else -> error("invalid_ledger_event")
        } }
        val remaining = Math.subtractExact(obligation.minorUnits, net)
        require(minorUnits <= remaining) { "settlement_exceeds_balance" }
        dao.insertAdjustment(MoneyAdjustmentRow(eventId, commandId, obligationId, "settlement", minorUnits, currency, occurredAt, null, reason, scope))
        remaining - minorUnits
    }

    suspend fun createCalendarEvent(input: CalendarInput): LocalCalendarEventRow = db.withTransaction {
        val row = validatedCalendarRow(input, 1)
        dao.insertCalendarEvent(row); row
    }

    suspend fun updateCalendarEvent(scope: String, input: CalendarInput, expectedVersion: Long): LocalCalendarEventRow = db.withTransaction {
        val current = dao.calendarEvent(scope, input.eventId) ?: error("not_found")
        check(current.version == expectedVersion && expectedVersion < Long.MAX_VALUE) { "version_conflict" }
        check(input.scopeId == scope)
        val next = validatedCalendarRow(input, expectedVersion + 1)
        check(dao.updateCalendarEvent(scope, input.eventId, expectedVersion, next.version, next.title, next.startLocal,
            next.endLocal, next.zoneId, next.timePrecision) == 1) { "version_conflict" }
        next
    }

    suspend fun calendarBetween(scope: String, fromLocal: String, untilLocal: String): List<LocalCalendarEventRow> {
        require(fromLocal < untilLocal)
        return (dao.calendarBetween(scope, fromLocal, untilLocal) + dao.allDayCalendarBetween(scope, fromLocal, untilLocal))
            .distinctBy { it.eventId }.sortedWith(compareBy<LocalCalendarEventRow> { it.startLocal }.thenBy { it.eventId })
    }

    private fun validatedCalendarRow(input: CalendarInput, version: Long): LocalCalendarEventRow {
        requireTitle(input.title); require(input.precision in setOf("date", "minute"))
        val zone = ZoneId.of(input.zoneId)
        if (input.precision == "date") {
            java.time.LocalDate.parse(input.startLocal)
            require(input.endLocal == null)
        } else {
            val start = LocalDateTime.parse(input.startLocal)
            check(zone.rules.getValidOffsets(start).size == 1) { "ambiguous_or_invalid_local_time" }
            input.endLocal?.let { end ->
                val endTime = LocalDateTime.parse(end); require(endTime.isAfter(start))
                check(zone.rules.getValidOffsets(endTime).size == 1) { "ambiguous_or_invalid_local_time" }
            }
        }
        return LocalCalendarEventRow(input.eventId, input.scopeId, input.title.trim(), input.startLocal, input.endLocal, input.zoneId, input.precision, version, "active")
    }

    private fun validateSchema(schema: List<ColumnSpec>) {
        require(schema.size in 1..50 && schema.map { it.id }.toSet().size == schema.size)
        schema.forEach { col -> require(col.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))); requireTitle(col.name); require(col.config.toString().length <= 1000) }
    }

    private fun validateRow(columns: Map<String, ColumnSpec>, values: Map<String, Any?>) {
        require(values.keys.all { it in columns })
        columns.forEach { (id, col) ->
            val value = values[id]
            if (value == null) { require(!col.required) { "required_value_missing" }; return@forEach }
            when (col.type) {
                ColumnType.TEXT, ColumnType.PERSON_REF, ColumnType.PROJECT_REF -> require(value is String && value.length <= 10_000)
                ColumnType.INTEGER -> require(value is Int || value is Long)
                ColumnType.DECIMAL -> require(value is String && runCatching { BigDecimal(value); true }.getOrDefault(false))
                ColumnType.BOOLEAN -> require(value is Boolean)
                ColumnType.DATE -> require(value is String && runCatching { java.time.LocalDate.parse(value); true }.getOrDefault(false))
                ColumnType.DATETIME -> require(value is String && runCatching { java.time.OffsetDateTime.parse(value); true }.getOrDefault(false))
                ColumnType.MONEY -> {
                    require(value is Map<*, *> && value.keys == setOf("minorUnits", "currency"))
                    require(value["minorUnits"] is Number && (value["minorUnits"] as Number).toLong() >= 0)
                    require(value["currency"] is String && isCurrency(value["currency"] as String))
                }
            }
        }
    }

    private fun encodeValues(values: Map<String, Any?>): String {
        val objectValue = JSONObject()
        values.toSortedMap().forEach { (key, value) -> objectValue.put(key, when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject(value.entries.associate { it.key.toString() to it.value }.toSortedMap())
            else -> value
        }) }
        return objectValue.toString()
    }

    private fun columnsJson(columns: List<TableColumnRow>) = JSONArray(columns.sortedBy { it.position }.map { JSONObject()
        .put("id", it.columnId).put("position", it.position).put("name", it.name).put("type", it.valueType)
        .put("required", it.required).put("config", JSONObject(it.configJson)) }).toString()

    /** Stable target-table scoped IDs make an identical import retry idempotent. */
    private fun deterministicImportId(tableId: String, sourceRowId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$tableId\u0000$sourceRowId".toByteArray())
        val hex = digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-a${hex.substring(17, 20)}-${hex.substring(20, 32)}"
    }

    private fun payload(title: String, body: String?, status: String, deletedAt: String?) = JSONObject()
        .put("title", title).put("body", body ?: JSONObject.NULL).put("status", status)
        .put("deletedAt", deletedAt ?: JSONObject.NULL).toString()
    private fun fold(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonValue(value.get(key)) }
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
    private fun requireTitle(value: String) { require(value.isNotBlank() && value == value.trim() && value.codePointCount(0, value.length) <= 200) }
    private fun isCurrency(value: String) = runCatching { Currency.getInstance(value).currencyCode == value }.getOrDefault(false)

    private fun toTaskRow(event: TaskEvent): TaskEventRow = when (event) {
        is TaskEvent.Created -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "created", previousVersion = null, title = event.title, completed = null, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.Renamed -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "renamed", previousVersion = event.previousVersion, title = event.title, completed = null, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.CompletionChanged -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "completed", previousVersion = event.previousVersion, title = null, completed = event.completed, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.DetailsChanged -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "details", previousVersion = event.previousVersion, title = null, completed = null, dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind, personLabel = event.personLabel, projectLabel = event.projectLabel)
        is TaskEvent.Updated -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId, commandId = event.commandId, type = "updated", previousVersion = event.previousVersion, title = event.title, completed = null, dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind, personLabel = event.personLabel, projectLabel = event.projectLabel)
    }

    private fun fromTaskRow(row: TaskEventRow): TaskEvent = when (row.type) {
        "created" -> TaskEvent.Created(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.title))
        "renamed" -> TaskEvent.Renamed(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.title))
        "completed" -> TaskEvent.CompletionChanged(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.completed))
        "details" -> TaskEvent.DetailsChanged(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), row.dueLocal, row.dueZone, requireNotNull(row.remind), row.personLabel, row.projectLabel)
        "updated" -> TaskEvent.Updated(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.title), row.dueLocal, row.dueZone, requireNotNull(row.remind), row.personLabel, row.projectLabel)
        else -> error("Unknown task event type")
    }
}

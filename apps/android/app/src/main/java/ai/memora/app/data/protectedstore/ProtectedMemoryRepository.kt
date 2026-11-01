package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import ai.memora.app.data.CaptureSnapshot
import ai.memora.app.domain.Task
import ai.memora.app.domain.TaskApply
import ai.memora.app.domain.TaskEvent
import ai.memora.app.domain.TaskRules
import ai.memora.app.domain.VoiceMoneySnapshot
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Validated snapshots read through the existing encrypted legacy-store readers. */
internal data class LegacyTaskSnapshot(val events: List<TaskEvent>, val deletedIds: Set<String>, val sourceSchema: Int = 2)
internal data class LegacyStoreBundle(
    val capture: CaptureSnapshot,
    val tasks: LegacyTaskSnapshot,
    val money: VoiceMoneySnapshot,
    /** SHA-256 of the original encrypted file bytes, never plaintext/export bytes. */
    val encryptedFileDigests: Map<String, String>,
    val sourceSchemas: Map<String, Int>,
)

internal sealed interface MigrationResult {
    data class Applied(val records: Int) : MigrationResult
    data object AlreadyApplied : MigrationResult
}

internal data class CanonicalTaskSnapshot(val tasks: List<Task>, val deletedIds: Set<String>)

/** One SQLCipher-backed local repository boundary. Does not change any app workflow or legacy file. */
internal class ProtectedMemoryRepository(private val db: AgentDatabase) {
    private val dao get() = db.protectedStoreDao()

    /** Migrate all three legacy stores atomically. Any validation/conflict/error rolls back all rows. */
    suspend fun migrateLegacy(bundle: LegacyStoreBundle): MigrationResult {
        validate(bundle)
        val expected = STORE_NAMES.map { name ->
            LegacyMigrationRow(name, bundle.encryptedFileDigests.getValue(name), bundle.sourceSchemas.getValue(name),
                count(bundle, name), true)
        }
        return db.withTransaction {
            val prior = dao.migrations()
            if (prior.isNotEmpty()) {
                check(prior == expected) { "Legacy migration fingerprint differs from verified migration" }
                return@withTransaction MigrationResult.AlreadyApplied
            }
            check(dao.draft() == null && dao.sources().isEmpty() && dao.taskEvents().isEmpty() &&
                dao.people().isEmpty() && dao.moneyEvents().isEmpty() && dao.tombstones().isEmpty() && dao.deletedTasks().isEmpty()) {
                "Target store is not empty and has no migration manifest"
            }

            dao.putDraft(MemoryDraftRow(text = bundle.capture.draft, version = bundle.capture.draftVersion))
            val expectedSources = bundle.capture.captures.map { capture -> MemorySourceRow(
                capture.source.id, capture.commandId, capture.draftVersion, capture.zone, capture.origin.name,
                capture.archived, capture.organizationVersion, capture.source.scopeId,
            ) }.sortedBy { it.sourceId }
            dao.putSources(expectedSources)
            val expectedRevisions = bundle.capture.captures.flatMap { capture -> capture.source.revisions.map { revision ->
                MemoryRevisionRow(capture.source.id, revision.id, revision.number, revision.text, revision.recordedAt.toString())
            } }.sortedWith(compareBy<MemoryRevisionRow> { it.sourceId }.thenBy { it.number })
            dao.putRevisions(expectedRevisions)
            val expectedTombstones = bundle.capture.tombstones.map { tombstone ->
                MemoryTombstoneRow(tombstone.commandId, tombstone.sourceId, tombstone.revisionId,
                    JSONArray(tombstone.priorRevisionIds).toString())
            }.sortedBy { it.commandId }
            dao.putTombstones(expectedTombstones)
            val expectedTaskRows = bundle.tasks.events.map(::toRow)
            dao.putTaskEvents(expectedTaskRows)
            dao.putDeletedTasks(bundle.tasks.deletedIds.sorted().map(::DeletedTaskRow))
            val expectedPeople = bundle.money.persons.map { PersonRow(it.id, it.name) }.sortedBy { it.personId }
            dao.putPeople(expectedPeople)
            val expectedMoneyEvents = (
                bundle.money.receipts.map { MoneyEventRow(it.id, it.commandId, it.personId, "receipt", it.minorUnits,
                    it.currency, it.source, it.recordedAt.toString(), it.occurredAt?.toString()) } +
                    bundle.money.debts.map { MoneyEventRow(it.id, it.commandId, it.personId, "debt", it.minorUnits,
                        it.currency, it.source, it.recordedAt.toString(), null) }
                ).sortedBy { it.eventId }
            dao.putMoneyEvents(expectedMoneyEvents)
            dao.putMigrations(expected)

            check(dao.draft() == MemoryDraftRow(text = bundle.capture.draft, version = bundle.capture.draftVersion))
            check(dao.sources() == expectedSources)
            check(dao.revisions() == expectedRevisions)
            check(dao.tombstones() == expectedTombstones)
            check(dao.taskEvents().map(::fromRow) == bundle.tasks.events)
            check(dao.deletedTasks().toSet() == bundle.tasks.deletedIds)
            check(dao.people() == expectedPeople)
            check(dao.moneyEvents() == expectedMoneyEvents)
            check(dao.migrations() == expected)
            MigrationResult.Applied(expected.sumOf { it.itemCount })
        }
    }

    /** Replays the stored ordered event log; never trusts a cached/model-generated task projection. */
    suspend fun tasks(): CanonicalTaskSnapshot {
        val deleted = dao.deletedTasks().toSet()
        val projection = linkedMapOf<String, Task>()
        dao.taskEvents().forEach { row ->
            check(row.taskId !in deleted)
            val applied = TaskRules.apply(projection[row.taskId], fromRow(row)) as? TaskApply.Applied
                ?: error("Stored task event history is invalid")
            projection[row.taskId] = applied.task
        }
        return CanonicalTaskSnapshot(projection.values.toList(), deleted)
    }

    /** Journal contains recovery metadata only. Call only for a command with an unresolved effect. */
    suspend fun recordPendingCommand(commandId: String, digest: String, targetVersionsJson: String) = db.withTransaction {
        require(commandId.isNotBlank() && isSha256(digest) && targetVersionsJson.length <= 4096)
        validateVersions(targetVersionsJson)
        val current = dao.command(commandId)
        if (current != null) {
            check(current.digest == digest && current.targetVersionsJson == targetVersionsJson) { "Command ID collision" }
            return@withTransaction
        }
        dao.putCommand(AgentCommandJournalRow(commandId, digest, "pending", targetVersionsJson))
    }

    suspend fun forgetResolvedCommand(commandId: String) = dao.deleteCommand(commandId)

    /** Outbox metadata must not include prompt, transcript, source text or financial values. */
    suspend fun enqueue(operationId: String, commandId: String, digest: String, versionsJson: String) = db.withTransaction {
        require(operationId.isNotBlank() && commandId.isNotBlank() && isSha256(digest) && versionsJson.length <= 4096)
        validateVersions(versionsJson)
        val old = dao.outbox(operationId)
        if (old != null) {
            check(old.commandId == commandId && old.digest == digest && old.targetVersionsJson == versionsJson) {
                "Outbox operation ID collision"
            }
            return@withTransaction
        }
        dao.putOutbox(AgentOutboxRow(operationId, commandId, digest, "queued", versionsJson))
    }

    suspend fun updateOutbox(operationId: String, status: String) {
        require(status in setOf("queued", "in_flight", "retry", "blocked", "complete"))
        check(dao.outbox(operationId) != null) { "Unknown outbox operation" }
        dao.updateOutbox(operationId, status)
    }

    suspend fun removeOutbox(operationId: String) = dao.deleteOutbox(operationId)

    private fun validate(bundle: LegacyStoreBundle) {
        require(bundle.encryptedFileDigests.keys == STORE_NAMES.toSet())
        require(bundle.sourceSchemas.keys == STORE_NAMES.toSet())
        require(bundle.encryptedFileDigests.values.all(::isSha256))
        require(bundle.sourceSchemas["captures"] in 1..5)
        require(bundle.sourceSchemas["tasks"] in 1..2)
        require(bundle.sourceSchemas["voice-money"] in 1..2)

        val captures = bundle.capture
        require(captures.draftVersion >= 0)
        val activeSources = captures.captures.map { it.source.id }
        require(activeSources.distinct().size == activeSources.size)
        val tombstoneSources = captures.tombstones.map { it.sourceId }
        require(tombstoneSources.distinct().size == tombstoneSources.size && tombstoneSources.none { it in activeSources })
        val captureCommands = captures.captures.map { it.commandId } + captures.tombstones.map { it.commandId }
        require(captureCommands.none(String::isBlank) && captureCommands.distinct().size == captureCommands.size)
        val allRevisionIds = captures.captures.flatMap { it.source.revisions.map { r -> r.id } } +
            captures.tombstones.flatMap { listOf(it.revisionId) + it.priorRevisionIds }
        require(allRevisionIds.none(String::isBlank) && allRevisionIds.distinct().size == allRevisionIds.size)
        require(captures.tombstones.map { it.sourceId }.distinct().size == captures.tombstones.size)

        val events = bundle.tasks.events
        require(events.map { it.commandId }.distinct().size == events.size)
        require(bundle.tasks.deletedIds.none(String::isBlank) && bundle.tasks.deletedIds.size == bundle.tasks.deletedIds.distinct().size)
        val tasks = linkedMapOf<String, Task>()
        events.forEach { event ->
            require(event.taskId !in bundle.tasks.deletedIds)
            val applied = TaskRules.apply(tasks[event.taskId], event) as? TaskApply.Applied
                ?: error("Legacy task history failed deterministic replay")
            tasks[event.taskId] = applied.task
        }

        val money = bundle.money
        require(money.persons.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(money.persons.map { it.id }.distinct().size == money.persons.size)
        val eventsMoney = money.receipts.map { it.id to it.commandId } + money.debts.map { it.id to it.commandId }
        require(eventsMoney.map { it.first }.distinct().size == eventsMoney.size)
        require(eventsMoney.map { it.second }.distinct().size == eventsMoney.size)
        require((money.receipts.map { it.personId } + money.debts.map { it.personId }).all { id -> money.persons.any { it.id == id } })
        require((money.receipts.map { it.minorUnits } + money.debts.map { it.minorUnits }).all { it > 0 })
    }

    private fun count(bundle: LegacyStoreBundle, name: String): Int = when (name) {
        "captures" -> bundle.capture.captures.size + bundle.capture.tombstones.size + bundle.capture.captures.sumOf { it.source.revisions.size }
        "tasks" -> bundle.tasks.events.size + bundle.tasks.deletedIds.size
        else -> bundle.money.persons.size + bundle.money.receipts.size + bundle.money.debts.size
    }

    private fun toRow(event: TaskEvent) = when (event) {
        is TaskEvent.Created -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId,
            commandId = event.commandId, type = "created", previousVersion = null, title = event.title, completed = null,
            dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.Renamed -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId,
            commandId = event.commandId, type = "renamed", previousVersion = event.previousVersion, title = event.title, completed = null,
            dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.CompletionChanged -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId,
            commandId = event.commandId, type = "completed", previousVersion = event.previousVersion, title = null,
            completed = event.completed, dueLocal = null, dueZone = null, remind = null, personLabel = null, projectLabel = null)
        is TaskEvent.DetailsChanged -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId,
            commandId = event.commandId, type = "details", previousVersion = event.previousVersion, title = null, completed = null,
            dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind, personLabel = event.personLabel,
            projectLabel = event.projectLabel)
        is TaskEvent.Updated -> TaskEventRow(taskId = event.taskId, scopeId = event.scopeId, actorId = event.actorId,
            commandId = event.commandId, type = "updated", previousVersion = event.previousVersion, title = event.title,
            completed = null, dueLocal = event.dueLocal, dueZone = event.dueZone, remind = event.remind,
            personLabel = event.personLabel, projectLabel = event.projectLabel)
    }

    private fun fromRow(row: TaskEventRow): TaskEvent = when (row.type) {
        "created" -> TaskEvent.Created(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.title))
        "renamed" -> TaskEvent.Renamed(row.taskId, row.scopeId, row.actorId, row.commandId, requireNotNull(row.previousVersion), requireNotNull(row.title))
        "completed" -> TaskEvent.CompletionChanged(row.taskId, row.scopeId, row.actorId, row.commandId,
            requireNotNull(row.previousVersion), requireNotNull(row.completed))
        "details" -> TaskEvent.DetailsChanged(row.taskId, row.scopeId, row.actorId, row.commandId,
            requireNotNull(row.previousVersion), row.dueLocal, row.dueZone, requireNotNull(row.remind), row.personLabel, row.projectLabel)
        "updated" -> TaskEvent.Updated(row.taskId, row.scopeId, row.actorId, row.commandId,
            requireNotNull(row.previousVersion), requireNotNull(row.title), row.dueLocal, row.dueZone,
            requireNotNull(row.remind), row.personLabel, row.projectLabel)
        else -> error("Unknown task event type")
    }

    private fun isSha256(value: String) = value.matches(Regex("[0-9a-f]{64}"))

    private fun validateVersions(json: String) {
        val array = JSONArray(json)
        require(array.length() <= 100)
        val ids = hashSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("entityId", "version"))
            val id = item.getString("entityId")
            require(id.isNotBlank() && id.length <= 128 && ids.add(id))
            val version = item.get("version")
            require(version is Int || version is Long)
            require((version as Number).toLong() >= 0)
        }
    }

    companion object { private val STORE_NAMES = listOf("captures", "tasks", "voice-money") }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

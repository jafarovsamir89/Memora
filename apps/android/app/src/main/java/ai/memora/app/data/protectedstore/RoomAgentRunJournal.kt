package ai.memora.app.data.protectedstore

import androidx.room.withTransaction
import ai.memora.app.agent.AgentCommandJournalEntry
import ai.memora.app.agent.AgentRunJournal
import ai.memora.app.agent.AgentTurnJournalEntry
import ai.memora.app.agent.AgentTurnStatus
import org.json.JSONArray
import org.json.JSONObject

/** SQLCipher-backed status/recovery adapter. It never accepts or persists dialogue payloads. */
internal class RoomAgentRunJournal(
    private val db: AgentDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AgentRunJournal {
    private val dao get() = db.protectedStoreDao()

    override suspend fun beginTurn(turnId: String) = db.withTransaction {
        requireUuid(turnId)
        check(dao.turn(turnId) == null) { "Turn ID already exists" }
        dao.putTurn(AgentTurnJournalRow(turnId, AgentTurnStatus.THINKING.name, nowMillis()))
    }

    override suspend fun setTurnStatus(turnId: String, status: AgentTurnStatus) = db.withTransaction {
        requireUuid(turnId)
        if (status == AgentTurnStatus.RECOVERY_REQUIRED) {
            dao.putTurn(AgentTurnJournalRow(turnId, status.name, nowMillis()))
        } else {
            check(dao.turn(turnId) != null) { "Unknown turn" }
            dao.putTurn(AgentTurnJournalRow(turnId, status.name, nowMillis()))
        }
    }

    /** Resolved/transient turns are removed; only recovery metadata survives interruption. */
    override suspend fun finishTurn(turnId: String, status: AgentTurnStatus) = db.withTransaction {
        requireUuid(turnId)
        if (status == AgentTurnStatus.RECOVERY_REQUIRED) {
            dao.putTurn(AgentTurnJournalRow(turnId, status.name, nowMillis()))
        } else {
            dao.deleteTurn(turnId)
        }
    }

    override suspend fun recordCommand(command: AgentCommandJournalEntry) = db.withTransaction {
        requireUuid(command.commandId)
        require(command.payloadDigest.matches(Regex("[0-9a-f]{64}")))
        require(command.status in COMMAND_STATES)
        val encodedVersions = encodeVersions(command.targetVersions)
        val current = dao.command(command.commandId)
        if (current != null) {
            check(current.digest == command.payloadDigest && current.targetVersionsJson == encodedVersions) {
                "Command ID collision"
            }
        } else {
            dao.putCommand(AgentCommandJournalRow(command.commandId, command.payloadDigest, command.status, encodedVersions))
        }
    }

    override suspend fun setCommandStatus(commandId: String, expected: String, status: String) = db.withTransaction {
        requireUuid(commandId)
        require(expected in COMMAND_STATES && status in COMMAND_STATES)
        check(dao.transitionCommand(commandId, expected, status) == 1) { "Command status changed or command missing" }
    }

    override suspend fun interruptedTurns(): List<AgentTurnJournalEntry> = dao.turns()
        .mapNotNull { row ->
            val status = runCatching { AgentTurnStatus.valueOf(row.status) }.getOrNull() ?: return@mapNotNull null
            if (status in INTERRUPTED_STATES) AgentTurnJournalEntry(row.turnId, status, row.updatedAtMillis) else null
        }

    override suspend fun command(commandId: String): AgentCommandJournalEntry? {
        requireUuid(commandId)
        val row = dao.command(commandId) ?: return null
        val versions = decodeVersions(row.targetVersionsJson)
        return AgentCommandJournalEntry(row.commandId, row.digest, row.status, versions)
    }

    override suspend fun unresolvedCommands(): List<AgentCommandJournalEntry> = db.withTransaction {
        dao.unresolvedAgentCommands().map { row ->
            AgentCommandJournalEntry(row.commandId, row.digest, row.status, decodeVersions(row.targetVersionsJson))
        }
    }

    private fun encodeVersions(versions: Map<String, Long>): String {
        require(versions.size <= 100 && versions.keys.all(::isUuid) && versions.values.all { it >= 0 })
        val array = JSONArray()
        versions.toSortedMap().forEach { (id, version) ->
            array.put(JSONObject().put("id", id).put("version", version))
        }
        return array.toString().also { require(it.length <= 4096) }
    }

    private fun decodeVersions(json: String): Map<String, Long> {
        require(json.length <= 4096)
        val array = JSONArray(json)
        require(array.length() <= 100)
        val result = linkedMapOf<String, Long>()
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            require(item.length() == 2 && item.has("id") && item.has("version"))
            val id = item.getString("id")
            val version = item.getLong("version")
            require(isUuid(id) && version >= 0 && result.put(id, version) == null)
        }
        return result
    }

    private fun requireUuid(value: String) = require(isUuid(value))
    private fun isUuid(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

    private companion object {
        val COMMAND_STATES = setOf("prepared", "applying", "reconciling", "committed", "conflict", "rejected")
        val INTERRUPTED_STATES = setOf(AgentTurnStatus.THINKING, AgentTurnStatus.EXECUTING)
    }
}

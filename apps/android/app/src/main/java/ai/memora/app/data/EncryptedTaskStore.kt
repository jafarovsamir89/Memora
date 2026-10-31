package ai.memora.app.data

import ai.memora.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface StoreRead {
    data class Ready(val tasks: List<Task>) : StoreRead
    data object Unavailable : StoreRead
}

sealed interface StoreWrite {
    data object Recorded : StoreWrite
    data object Duplicate : StoreWrite
    data object Conflict : StoreWrite
    data object Unavailable : StoreWrite
}

sealed interface StoreExport {
    data class Ready(val json: String) : StoreExport
    data object Unavailable : StoreExport
}

/** Synthetic-data prototype. Key provider must not generate a key when create is false. */
class EncryptedTaskStore(private val path: File, private val keyProvider: (create: Boolean) -> SecretKey?) {
    private data class State(val events: List<TaskEvent>, val tasks: Map<String, Task>, val deleted: Set<String>)

    companion object {
        private val lock = Any() // Covers this app process only.
        private val header = byteArrayOf(0x4d, 0x54, 0x01)
        private const val MAX_BYTES = 1_048_576
        private const val MAX_EVENTS = 1000
    }

    fun load(): StoreRead = synchronized(lock) {
        try { StoreRead.Ready(readState().tasks.values.toList()) }
        catch (_: Exception) { StoreRead.Unavailable }
    }

    fun append(event: TaskEvent): StoreWrite = synchronized(lock) {
        try {
            val state = readState()
            if (event.taskId in state.deleted) return@synchronized StoreWrite.Conflict
            val prior = state.events.find { it.commandId == event.commandId }
            if (prior != null) return@synchronized if (prior == event) StoreWrite.Duplicate else StoreWrite.Conflict
            if (state.events.size >= MAX_EVENTS) return@synchronized StoreWrite.Unavailable
            val applied = TaskRules.apply(state.tasks[event.taskId], event)
            if (applied !is TaskApply.Applied) return@synchronized StoreWrite.Conflict
            writeState(state.events + event, state.deleted)
            StoreWrite.Recorded
        } catch (_: Exception) { StoreWrite.Unavailable }
    }

    fun deleteTask(id: String, expectedVersion: Long): StoreWrite = synchronized(lock) {
        try {
            val state = readState()
            val task = state.tasks[id] ?: return@synchronized StoreWrite.Conflict
            if (task.version != expectedVersion) return@synchronized StoreWrite.Conflict
            writeState(state.events.filterNot { it.taskId == id }, state.deleted + id)
            StoreWrite.Recorded
        } catch (_: Exception) { StoreWrite.Unavailable }
    }

    fun clearAll(): StoreWrite = synchronized(lock) {
        try {
            val state = readState()
            writeState(emptyList(), state.deleted + state.tasks.keys)
            StoreWrite.Recorded
        } catch (_: Exception) { StoreWrite.Unavailable }
    }

    fun export(): StoreExport = synchronized(lock) {
        try {
            val state = readState()
            val events = JSONArray().apply { state.events.forEach { put(encode(it)) } }
            StoreExport.Ready(JSONObject().put("schema", 1).put("format", "memora-tasks").put("events", events).toString(2))
        } catch (_: Exception) { StoreExport.Unavailable }
    }

    private fun writeState(events: List<TaskEvent>, deleted: Set<String>) {
        require(events.size <= MAX_EVENTS && deleted.size <= MAX_EVENTS)
        val obj = JSONObject().put("schema", 2)
            .put("events", JSONArray().apply { events.forEach { put(encode(it)) } })
            .put("deleted", JSONArray().apply { deleted.sorted().forEach { put(it) } })
        val key = keyProvider(!path.exists()) ?: error("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        val payload = header + cipher.iv + cipher.doFinal(obj.toString().toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12 && payload.size <= MAX_BYTES)
        val temp = File(path.path + ".new")
        try {
            FileOutputStream(temp).use { stream -> stream.write(payload); stream.fd.sync() }
            Files.move(temp.toPath(), path.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun readState(): State {
        if (!path.exists()) return State(emptyList(), emptyMap(), emptySet())
        if (path.length() > MAX_BYTES) error("limit")
        val data = path.readBytes()
        require(data.size in (header.size + 12 + 16)..MAX_BYTES && data.copyOfRange(0, header.size).contentEquals(header))
        val key = keyProvider(false) ?: error("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(header)
        val text = cipher.doFinal(data.copyOfRange(header.size + 12, data.size)).toString(Charsets.UTF_8)
        val deleted = hashSetOf<String>()
        val array = if (text.trimStart().startsWith("[")) JSONArray(text) else {
            val obj = JSONObject(text)
            require(obj.getInt("schema") == 2)
            val removed = obj.getJSONArray("deleted")
            require(removed.length() <= MAX_EVENTS)
            for (i in 0 until removed.length()) require(deleted.add(removed.getString(i)) && removed.getString(i).isNotBlank())
            obj.getJSONArray("events")
        }
        require(array.length() <= MAX_EVENTS)
        val events = ArrayList<TaskEvent>()
        val tasks = linkedMapOf<String, Task>()
        val commands = hashSetOf<String>()
        for (index in 0 until array.length()) {
            val event = decode(array.getJSONObject(index))
            require(event.taskId !in deleted)
            require(commands.add(event.commandId))
            val applied = TaskRules.apply(tasks[event.taskId], event) as? TaskApply.Applied ?: error("invalid event")
            tasks[event.taskId] = applied.task
            events.add(event)
        }
        return State(events, tasks, deleted)
    }

    private fun encode(e: TaskEvent): JSONObject = JSONObject().apply {
        put("id", e.taskId); put("scope", e.scopeId); put("actor", e.actorId); put("command", e.commandId)
        when (e) {
            is TaskEvent.Created -> { put("type", "created"); put("title", e.title) }
            is TaskEvent.Renamed -> { put("type", "renamed"); put("version", e.previousVersion); put("title", e.title) }
            is TaskEvent.CompletionChanged -> { put("type", "completed"); put("version", e.previousVersion); put("value", e.completed) }
            is TaskEvent.DetailsChanged -> {
                put("type", "details"); put("version", e.previousVersion)
                put("due", e.dueLocal ?: JSONObject.NULL); put("zone", e.dueZone ?: JSONObject.NULL); put("remind", e.remind)
                put("person", e.personLabel ?: JSONObject.NULL); put("project", e.projectLabel ?: JSONObject.NULL)
            }
            is TaskEvent.Updated -> {
                put("type", "updated"); put("version", e.previousVersion); put("title", e.title)
                put("due", e.dueLocal ?: JSONObject.NULL); put("zone", e.dueZone ?: JSONObject.NULL); put("remind", e.remind)
                put("person", e.personLabel ?: JSONObject.NULL); put("project", e.projectLabel ?: JSONObject.NULL)
            }
        }
    }

    private fun decode(o: JSONObject): TaskEvent {
        val id = o.getString("id"); val scope = o.getString("scope"); val actor = o.getString("actor"); val cmd = o.getString("command")
        return when (o.getString("type")) {
            "created" -> TaskEvent.Created(id, scope, actor, cmd, o.getString("title"))
            "renamed" -> TaskEvent.Renamed(id, scope, actor, cmd, o.getLong("version"), o.getString("title"))
            "completed" -> TaskEvent.CompletionChanged(id, scope, actor, cmd, o.getLong("version"), o.getBoolean("value"))
            "details" -> TaskEvent.DetailsChanged(id, scope, actor, cmd, o.getLong("version"),
                if (o.isNull("due")) null else o.getString("due"), if (o.isNull("zone")) null else o.getString("zone"),
                o.getBoolean("remind"), if (o.isNull("person")) null else o.getString("person"),
                if (o.isNull("project")) null else o.getString("project"))
            "updated" -> TaskEvent.Updated(id, scope, actor, cmd, o.getLong("version"), o.getString("title"),
                if (o.isNull("due")) null else o.getString("due"), if (o.isNull("zone")) null else o.getString("zone"),
                o.getBoolean("remind"), if (o.isNull("person")) null else o.getString("person"),
                if (o.isNull("project")) null else o.getString("project"))
            else -> error("unknown event")
        }
    }
}

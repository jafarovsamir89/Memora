package ai.memora.app.data

import ai.memora.app.domain.SourceChange
import ai.memora.app.domain.TextSource
import ai.memora.app.domain.TextSourceRules
import ai.memora.app.domain.TextRevision
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Local synthetic-data prototype; not a server queue or an authorization boundary. */
enum class CaptureOrigin { LOCAL, IMPORTED }
data class CaptureEntry(val commandId: String, val draftVersion: Long?, val zone: String, val source: TextSource,
    val origin: CaptureOrigin = CaptureOrigin.LOCAL, val archived: Boolean = false, val organizationVersion: Long = 0)
data class CaptureArchiveSelection(val sourceId: String, val revisionId: String, val organizationVersion: Long)
data class CaptureTombstone(val commandId: String, val sourceId: String, val revisionId: String,
    val priorRevisionIds: List<String> = emptyList())
data class CaptureSnapshot(val draft: String, val draftVersion: Long, val captures: List<CaptureEntry>,
    val tombstones: List<CaptureTombstone> = emptyList())

sealed interface CaptureExport {
    data class Ready(val json: String) : CaptureExport
    data object Unavailable : CaptureExport
}

data class CaptureImportTicket(val fileDigest: String, val storeDigest: String)
sealed interface CaptureImportPreview {
    data class Ready(val newSources: Int, val existingSources: Int, val restoresDraft: Boolean,
        val ticket: CaptureImportTicket, val samples: List<String>, val draftSample: String?) : CaptureImportPreview
    data object Invalid : CaptureImportPreview
    data object Conflict : CaptureImportPreview
    data object LimitReached : CaptureImportPreview
    data object Unavailable : CaptureImportPreview
}

sealed interface CaptureRead {
    data class Ready(val snapshot: CaptureSnapshot) : CaptureRead
    data object Unavailable : CaptureRead
}

sealed interface CaptureWrite {
    data object Recorded : CaptureWrite
    data object Unchanged : CaptureWrite
    data object Duplicate : CaptureWrite
    data object Conflict : CaptureWrite
    data object Invalid : CaptureWrite
    data object LimitReached : CaptureWrite
    data object Unavailable : CaptureWrite
}

private class CaptureLimitException : Exception()

/** Each mutation rewrites the bounded encrypted snapshot atomically within this process. */
class EncryptedCaptureStore(private val path: File, private val keyProvider: (Boolean) -> SecretKey?) {
    private data class ImportRecord(val source: TextSource, val zone: String, val archived: Boolean?, val versioned: Boolean = false)
    private data class ImportDocument(val draft: String, val records: List<ImportRecord>)
    private data class ImportPlan(val next: CaptureSnapshot, val newSources: Int, val existingSources: Int, val restoresDraft: Boolean)
    private class ImportConflict : Exception()
    private class ImportInvalid : Exception()
    companion object {
        private val lock = Any()
        private val header = byteArrayOf(0x4d, 0x43, 0x01)
        private const val MAX_BYTES = 1_048_576
        private const val MAX_CAPTURES = 500
        private const val MAX_REVISIONS = 100
    }

    fun load(): CaptureRead = synchronized(lock) {
        try { CaptureRead.Ready(read()) } catch (_: Exception) { CaptureRead.Unavailable }
    }

    fun saveDraft(expectedVersion: Long, text: String): CaptureWrite = synchronized(lock) {
        try {
            if (!TextSourceRules.validDraft(text)) return@synchronized CaptureWrite.Invalid
            val state = read()
            if (state.draftVersion != expectedVersion) return@synchronized CaptureWrite.Conflict
            if (state.draft == text) return@synchronized CaptureWrite.Unchanged
            if (state.draftVersion == Long.MAX_VALUE) return@synchronized CaptureWrite.Invalid
            write(state.copy(draft = text, draftVersion = state.draftVersion + 1))
            CaptureWrite.Recorded
        } catch (_: CaptureLimitException) { CaptureWrite.LimitReached }
        catch (_: Exception) { CaptureWrite.Unavailable }
    }

    /** A retry must reuse every argument, including the timestamp and source/revision IDs. */
    fun submit(commandId: String, expectedVersion: Long, sourceId: String, revisionId: String,
        text: String, recordedAt: Instant, zone: String): CaptureWrite = synchronized(lock) {
        try {
            if (commandId.isBlank() || sourceId.isBlank() || revisionId.isBlank() || expectedVersion < 0 ||
                runCatching { ZoneId.of(zone) }.isFailure) return@synchronized CaptureWrite.Invalid
            val created = TextSourceRules.create(sourceId, "local", revisionId, text, recordedAt) as? SourceChange.Changed
                ?: return@synchronized CaptureWrite.Invalid
            val state = read()
            val request = CaptureEntry(commandId, expectedVersion, zone, created.source)
            if (state.tombstones.any { it.commandId == commandId || it.sourceId == sourceId || it.revisionId == revisionId || revisionId in it.priorRevisionIds })
                return@synchronized CaptureWrite.Conflict
            val previous = state.captures.find { it.commandId == commandId }
            if (previous != null) return@synchronized if (previous.copy(source = previous.source.copy(revisions = previous.source.revisions.take(1)), archived = false, organizationVersion = 0) == request) CaptureWrite.Duplicate else CaptureWrite.Conflict
            if (state.captures.any { it.source.id == sourceId || it.source.revisions.any { revision -> revision.id == revisionId } })
                return@synchronized CaptureWrite.Conflict
            if (state.draftVersion != expectedVersion || state.draft != text) return@synchronized CaptureWrite.Conflict
            if (state.draftVersion == Long.MAX_VALUE || state.captures.size + state.tombstones.size >= MAX_CAPTURES) return@synchronized CaptureWrite.LimitReached
            write(state.copy(draft = "", draftVersion = state.draftVersion + 1, captures = state.captures + request))
            CaptureWrite.Recorded
        } catch (_: CaptureLimitException) { CaptureWrite.LimitReached }
        catch (_: Exception) { CaptureWrite.Unavailable }
    }

    fun delete(sourceId: String, expectedRevisionId: String): CaptureWrite = synchronized(lock) {
        try {
            if (sourceId.isBlank() || expectedRevisionId.isBlank()) return@synchronized CaptureWrite.Invalid
            val state = read()
            val entry = state.captures.find { it.source.id == sourceId }
            if (entry == null) {
                val removed = state.tombstones.find { it.sourceId == sourceId }
                return@synchronized if (removed?.revisionId == expectedRevisionId) CaptureWrite.Duplicate else CaptureWrite.Conflict
            }
            val revision = entry.source.revisions.last()
            if (revision.id != expectedRevisionId) return@synchronized CaptureWrite.Conflict
            val tombstone = CaptureTombstone(entry.commandId, sourceId, revision.id, entry.source.revisions.dropLast(1).map { it.id })
            write(state.copy(captures = state.captures.filterNot { it.source.id == sourceId }, tombstones = state.tombstones + tombstone))
            CaptureWrite.Recorded
        } catch (_: CaptureLimitException) { CaptureWrite.LimitReached }
        catch (_: Exception) { CaptureWrite.Unavailable }
    }

    /** The new revision ID is also the idempotency key; the exact prior revision and payload must match. */
    fun revise(sourceId: String, expectedRevisionId: String, revisionId: String, text: String, recordedAt: Instant): CaptureWrite = synchronized(lock) {
        try {
            if (listOf(sourceId, expectedRevisionId, revisionId).any { it.isBlank() }) return@synchronized CaptureWrite.Invalid
            val state = read()
            if (state.tombstones.any { it.sourceId == sourceId || it.revisionId == revisionId || revisionId in it.priorRevisionIds }) return@synchronized CaptureWrite.Conflict
            val entry = state.captures.find { it.source.id == sourceId } ?: return@synchronized CaptureWrite.Conflict
            val priorIndex = entry.source.revisions.indexOfFirst { it.id == revisionId }
            if (priorIndex >= 0) {
                val prior = entry.source.revisions[priorIndex]
                return@synchronized if (priorIndex > 0 && entry.source.revisions[priorIndex - 1].id == expectedRevisionId && prior.text == text && prior.recordedAt == recordedAt) CaptureWrite.Duplicate else CaptureWrite.Conflict
            }
            if (state.captures.any { it.source.revisions.any { rev -> rev.id == revisionId } }) return@synchronized CaptureWrite.Conflict
            val last = entry.source.revisions.last()
            if (last.id != expectedRevisionId) return@synchronized CaptureWrite.Conflict
            if (entry.source.revisions.size >= MAX_REVISIONS) return@synchronized CaptureWrite.LimitReached
            when (val change = TextSourceRules.revise(entry.source, last.number, revisionId, text, recordedAt)) {
                is SourceChange.Changed -> {
                    write(state.copy(captures = state.captures.map { if (it.source.id == sourceId) it.copy(source = change.source) else it }))
                    CaptureWrite.Recorded
                }
                SourceChange.Unchanged -> CaptureWrite.Unchanged
                SourceChange.Conflict -> CaptureWrite.Conflict
                SourceChange.Invalid -> CaptureWrite.Invalid
            }
        } catch (_: CaptureLimitException) { CaptureWrite.LimitReached }
        catch (_: Exception) { CaptureWrite.Unavailable }
    }

    /** A batch is applied entirely or not at all, against the exact displayed versions. */
    fun archive(selections: List<CaptureArchiveSelection>, archived: Boolean): CaptureWrite = synchronized(lock) {
        try {
            if (selections.isEmpty() || selections.size > MAX_CAPTURES || selections.any {
                    it.sourceId.isBlank() || it.revisionId.isBlank() || it.organizationVersion < 0
                } || selections.map { it.sourceId }.toSet().size != selections.size) return@synchronized CaptureWrite.Invalid
            val state = read()
            val byId = state.captures.associateBy { it.source.id }
            for (selection in selections) {
                val entry = byId[selection.sourceId] ?: return@synchronized CaptureWrite.Conflict
                if (entry.source.revisions.last().id != selection.revisionId || entry.organizationVersion != selection.organizationVersion)
                    return@synchronized CaptureWrite.Conflict
                if (entry.archived != archived && entry.organizationVersion == Long.MAX_VALUE) return@synchronized CaptureWrite.LimitReached
            }
            val selected = selections.map { it.sourceId }.toSet()
            if (state.captures.none { it.source.id in selected && it.archived != archived }) return@synchronized CaptureWrite.Unchanged
            val updated = state.captures.map { entry ->
                if (entry.source.id in selected && entry.archived != archived)
                    entry.copy(archived = archived, organizationVersion = entry.organizationVersion + 1) else entry
            }
            write(state.copy(captures = updated))
            CaptureWrite.Recorded
        } catch (_: CaptureLimitException) { CaptureWrite.LimitReached }
        catch (_: Exception) { CaptureWrite.Unavailable }
    }

    fun export(): CaptureExport = synchronized(lock) {
        try {
            val state = read()
            val versioned = state.captures.any { it.source.revisions.size > 1 }
            val entries = JSONArray()
            state.captures.forEach { entry ->
                val obj = JSONObject().put("id", entry.source.id).put("zone", entry.zone).put("archived", entry.archived)
                if (versioned) {
                    val revisions = JSONArray()
                    entry.source.revisions.forEach { revisions.put(JSONObject().put("id", it.id).put("number", it.number)
                        .put("text", it.text).put("recordedAt", it.recordedAt.toString())) }
                    obj.put("revisions", revisions)
                } else {
                    val rev = entry.source.revisions.single()
                    obj.put("revision", rev.id).put("text", rev.text).put("recordedAt", rev.recordedAt.toString())
                }
                entries.put(obj)
            }
            CaptureExport.Ready(JSONObject().put("format", "memora-captures").put("schema", if (versioned) 3 else 2)
                .put("draft", state.draft).put("captures", entries).toString(2))
        } catch (_: Exception) { CaptureExport.Unavailable }
    }

    /** Validates the complete export and binds the preview to both bytes and local state. */
    fun previewImport(json: String): CaptureImportPreview = synchronized(lock) {
        try {
            val document = parseImport(json)
            val state = read()
            val plan = planImport(state, document)
            CaptureImportPreview.Ready(plan.newSources, plan.existingSources, plan.restoresDraft,
                CaptureImportTicket(digest(json.toByteArray(Charsets.UTF_8)), storeDigest()),
                document.records.take(3).map { excerpt(it.source.revisions.last().text) },
                if (plan.restoresDraft) excerpt(document.draft) else null)
        } catch (_: ImportInvalid) { CaptureImportPreview.Invalid }
        catch (_: ImportConflict) { CaptureImportPreview.Conflict }
        catch (_: CaptureLimitException) { CaptureImportPreview.LimitReached }
        catch (_: Exception) { CaptureImportPreview.Unavailable }
    }

    /** Rechecks both preview inputs and applies the entire import or leaves the store unchanged. */
    fun applyImport(json: String, ticket: CaptureImportTicket): CaptureWrite = synchronized(lock) {
        try {
            val document = parseImport(json)
            val state = read()
            if (ticket.fileDigest != digest(json.toByteArray(Charsets.UTF_8)) || ticket.storeDigest != storeDigest())
                return@synchronized CaptureWrite.Conflict
            val plan = planImport(state, document)
            if (plan.next == state) return@synchronized CaptureWrite.Unchanged
            write(plan.next)
            CaptureWrite.Recorded
        } catch (_: ImportInvalid) { CaptureWrite.Invalid }
        catch (_: ImportConflict) { CaptureWrite.Conflict }
        catch (_: CaptureLimitException) { CaptureWrite.LimitReached }
        catch (_: Exception) { CaptureWrite.Unavailable }
    }

    private fun parseImport(json: String): ImportDocument {
        try {
            if (json.length > CaptureImportReader.MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > CaptureImportReader.MAX_BYTES) throw ImportLimitMarker()
            var format: String? = null
            var schema: Int? = null
            var draft: String? = null
            var records: List<ImportRecord>? = null
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                val fields = hashSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(fields.add(name))
                    when (name) {
                        "format" -> format = reader.readStrictString()
                        "schema" -> { require(reader.peek() == JsonToken.NUMBER); schema = reader.nextInt() }
                        "draft" -> draft = reader.readStrictString()
                        "captures" -> {
                            val sources = hashSetOf<String>()
                            val revisions = hashSetOf<String>()
                            val parsed = arrayListOf<ImportRecord>()
                            reader.beginArray()
                            while (reader.hasNext()) {
                                if (parsed.size >= MAX_CAPTURES) throw ImportLimitMarker()
                                val values = hashMapOf<String, Any>()
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val field = reader.nextName()
                                    require(field in setOf("id", "revision", "text", "recordedAt", "zone", "archived", "revisions") && field !in values)
                                    values[field] = when (field) {
                                        "archived" -> { require(reader.peek() == JsonToken.BOOLEAN); reader.nextBoolean() }
                                        "revisions" -> readImportHistory(reader)
                                        else -> reader.readStrictString()
                                    }
                                }
                                reader.endObject()
                                val id = values.getValue("id") as String
                                require(canonicalUuid(id) && sources.add(id))
                                val zone = values.getValue("zone") as String
                                ZoneId.of(zone)
                                val versioned = values.containsKey("revisions")
                                val history: List<TextRevision> = if (versioned) {
                                    require(values.keys == setOf("id", "zone", "archived", "revisions"))
                                    @Suppress("UNCHECKED_CAST")
                                    values.getValue("revisions") as List<TextRevision>
                                } else {
                                    require(values.keys == setOf("id", "zone", "revision", "text", "recordedAt") || values.keys == setOf("id", "zone", "revision", "text", "recordedAt", "archived"))
                                    listOf(TextRevision(values.getValue("revision") as String, 1, values.getValue("text") as String, Instant.parse(values.getValue("recordedAt") as String)))
                                }
                                var source: TextSource? = null
                                history.forEachIndexed { index, rev ->
                                    require(canonicalUuid(rev.id) && revisions.add(rev.id) && rev.number == index.toLong() + 1)
                                    val change = if (source == null) TextSourceRules.create(id, "local", rev.id, rev.text, rev.recordedAt)
                                        else TextSourceRules.revise(requireNotNull(source), index.toLong(), rev.id, rev.text, rev.recordedAt)
                                    source = (change as? SourceChange.Changed)?.source ?: error("invalid revision")
                                }
                                parsed.add(ImportRecord(requireNotNull(source), zone, values["archived"] as? Boolean, versioned))
                            }
                            reader.endArray()
                            records = parsed
                        }
                        else -> error("unknown field")
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            require(format == "memora-captures" && schema != null && schema in 1..3 && draft != null && records != null)
            require(requireNotNull(records).all { (if (schema == 1) it.archived == null else it.archived != null) && it.versioned == (schema == 3) })
            require(TextSourceRules.validDraft(requireNotNull(draft)))
            return ImportDocument(requireNotNull(draft), requireNotNull(records))
        } catch (_: ImportLimitMarker) { throw CaptureLimitException() }
        catch (_: Exception) { throw ImportInvalid() }
    }

    private class ImportLimitMarker : Exception()

    private fun readImportHistory(reader: JsonReader): List<TextRevision> {
        val history = arrayListOf<TextRevision>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (history.size >= MAX_REVISIONS) throw ImportLimitMarker()
            val fields = hashSetOf<String>()
            var id: String? = null
            var number: Long? = null
            var text: String? = null
            var at: Instant? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(fields.add(name))
                when (name) {
                    "id" -> id = reader.readStrictString()
                    "number" -> { require(reader.peek() == JsonToken.NUMBER); number = reader.nextLong() }
                    "text" -> text = reader.readStrictString()
                    "recordedAt" -> at = Instant.parse(reader.readStrictString())
                    else -> error("unknown revision field")
                }
            }
            reader.endObject()
            history.add(TextRevision(requireNotNull(id), requireNotNull(number), requireNotNull(text), requireNotNull(at)))
        }
        reader.endArray()
        require(history.isNotEmpty())
        return history
    }

    private fun JsonReader.readStrictString(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }

    private fun canonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun planImport(state: CaptureSnapshot, document: ImportDocument): ImportPlan {
        if (state.draft.isNotEmpty() && document.draft.isNotEmpty() && state.draft != document.draft) throw ImportConflict()
        val restoreDraft = state.draft.isEmpty() && document.draft.isNotEmpty()
        if (restoreDraft && state.draftVersion == Long.MAX_VALUE) throw CaptureLimitException()
        val added = arrayListOf<CaptureEntry>()
        var existing = 0
        document.records.forEach { record ->
            val id = record.source.id
            val revisionIds = record.source.revisions.map { it.id }.toSet()
            if (state.tombstones.any { it.sourceId == id || it.revisionId in revisionIds || it.priorRevisionIds.any(revisionIds::contains) }) throw ImportConflict()
            val prior = state.captures.find { it.source.id == id }
            if (prior != null) {
                if (prior.source != record.source || prior.zone != record.zone) throw ImportConflict()
                existing++
            } else {
                val command = "import:$id"
                if (state.captures.any { it.commandId == command || it.source.revisions.any { rev -> rev.id in revisionIds } } ||
                    state.tombstones.any { it.commandId == command } || added.any { it.commandId == command }) throw ImportConflict()
                added.add(CaptureEntry(command, null, record.zone, record.source, CaptureOrigin.IMPORTED, archived = record.archived ?: false))
            }
        }
        if (state.captures.size + state.tombstones.size + added.size > MAX_CAPTURES) throw CaptureLimitException()
        return ImportPlan(state.copy(draft = if (restoreDraft) document.draft else state.draft,
            draftVersion = if (restoreDraft) state.draftVersion + 1 else state.draftVersion,
            captures = state.captures + added), added.size, existing, restoreDraft)
    }

    private fun storeDigest(): String = if (!path.exists()) "absent" else digest(path.readBytes())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun excerpt(text: String): String {
        val count = text.codePointCount(0, text.length)
        val end = text.offsetByCodePoints(0, minOf(count, 120))
        return text.substring(0, end) + if (count > 120) "…" else ""
    }

    private fun write(state: CaptureSnapshot) {
        val entries = JSONArray()
        state.captures.forEach { entry ->
            val revisionList = JSONArray()
            entry.source.revisions.forEach { revision -> revisionList.put(JSONObject().put("id", revision.id)
                .put("number", revision.number).put("text", revision.text).put("recordedAt", revision.recordedAt.toString())) }
            entries.put(JSONObject().put("command", entry.commandId).put("draftVersion", entry.draftVersion ?: JSONObject.NULL)
                .put("source", entry.source.id).put("revisions", revisionList)
                .put("zone", entry.zone).put("origin", entry.origin.name)
                .put("archived", entry.archived).put("organizationVersion", entry.organizationVersion))
        }
        val removed = JSONArray()
        state.tombstones.forEach { removed.put(JSONObject().put("command", it.commandId).put("source", it.sourceId)
            .put("revision", it.revisionId).put("priorRevisions", JSONArray(it.priorRevisionIds))) }
        val plain = JSONObject().put("schema", 5).put("draft", state.draft)
            .put("draftVersion", state.draftVersion).put("captures", entries).put("deleted", removed).toString().toByteArray(Charsets.UTF_8)
        val key = keyProvider(!path.exists()) ?: error("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        val encrypted = header + cipher.iv + cipher.doFinal(plain)
        require(cipher.iv.size == 12)
        if (encrypted.size > MAX_BYTES) throw CaptureLimitException()
        val temp = File(path.path + ".new")
        try {
            FileOutputStream(temp).use { it.write(encrypted); it.fd.sync() }
            Files.move(temp.toPath(), path.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    private fun read(): CaptureSnapshot {
        if (!path.exists()) return CaptureSnapshot("", 0, emptyList())
        require(path.length() in (header.size + 12 + 16).toLong()..MAX_BYTES.toLong())
        val bytes = path.readBytes()
        require(bytes.copyOfRange(0, header.size).contentEquals(header))
        val key = keyProvider(false) ?: error("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(header)
        val obj = JSONObject(cipher.doFinal(bytes.copyOfRange(header.size + 12, bytes.size)).toString(Charsets.UTF_8))
        val schema = obj.getInt("schema")
        require(schema in 1..5)
        val draft = obj.getString("draft")
        val version = obj.getLong("draftVersion")
        require(TextSourceRules.validDraft(draft) && version >= 0)
        val array = obj.getJSONArray("captures")
        val deleted = if (schema >= 2) obj.getJSONArray("deleted") else JSONArray()
        require(array.length() + deleted.length() <= MAX_CAPTURES)
        val commands = hashSetOf<String>()
        val sources = hashSetOf<String>()
        val revisions = hashSetOf<String>()
        val captures = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val cmd = item.getString("command")
            val sourceId = item.getString("source")
            require(item.has("draftVersion"))
            val draftVersion = if (item.isNull("draftVersion")) null else item.getLong("draftVersion")
            val origin = if (schema < 3) CaptureOrigin.LOCAL else CaptureOrigin.valueOf(item.getString("origin"))
            val archived = if (schema < 4) false else item.getBoolean("archived")
            val organizationVersion = if (schema < 4) 0 else item.getLong("organizationVersion")
            require(organizationVersion >= 0)
            val zone = item.getString("zone")
            require(cmd.isNotBlank() && commands.add(cmd) && sources.add(sourceId))
            require(if (origin == CaptureOrigin.LOCAL) draftVersion != null && draftVersion in 0 until version else draftVersion == null)
            ZoneId.of(zone)
            val source = if (schema < 5) {
                val revisionId = item.getString("revision")
                require(revisions.add(revisionId))
                val changed = TextSourceRules.create(sourceId, "local", revisionId, item.getString("text"), Instant.parse(item.getString("recordedAt")))
                (changed as? SourceChange.Changed)?.source ?: error("invalid source")
            } else {
                val history = item.getJSONArray("revisions")
                require(history.length() in 1..MAX_REVISIONS)
                var result: TextSource? = null
                for (i in 0 until history.length()) {
                    val rev = history.getJSONObject(i)
                    val id = rev.getString("id")
                    require(revisions.add(id) && rev.getLong("number") == i.toLong() + 1)
                    val text = rev.getString("text")
                    val at = Instant.parse(rev.getString("recordedAt"))
                    val change = if (result == null) TextSourceRules.create(sourceId, "local", id, text, at)
                        else TextSourceRules.revise(result, i.toLong(), id, text, at)
                    result = (change as? SourceChange.Changed)?.source ?: error("invalid revision")
                }
                requireNotNull(result)
            }
            CaptureEntry(cmd, draftVersion, zone, source, origin, archived, organizationVersion)
        }
        val tombstones = (0 until deleted.length()).map { index ->
            val item = deleted.getJSONObject(index)
            val cmd = item.getString("command")
            val source = item.getString("source")
            val revision = item.getString("revision")
            require(cmd.isNotBlank() && source.isNotBlank() && revision.isNotBlank())
            require(commands.add(cmd) && sources.add(source) && revisions.add(revision))
            val previous = if (schema < 5) emptyList() else {
                val array = item.getJSONArray("priorRevisions")
                require(array.length() < MAX_REVISIONS)
                (0 until array.length()).map { i -> array.getString(i).also { require(it.isNotBlank() && revisions.add(it)) } }
            }
            CaptureTombstone(cmd, source, revision, previous)
        }
        return CaptureSnapshot(draft, version, captures, tombstones)
    }
}

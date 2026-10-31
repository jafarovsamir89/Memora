package ai.memora.app.data

import ai.memora.app.domain.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

sealed interface VoiceStoreRead {
    data class Ready(val snapshot: VoiceMoneySnapshot) : VoiceStoreRead
    data object Unavailable : VoiceStoreRead
}
sealed interface VoiceStoreWrite {
    data object Saved : VoiceStoreWrite
    data object Duplicate : VoiceStoreWrite
    data object Invalid : VoiceStoreWrite
    data object Ambiguous : VoiceStoreWrite
    data object Conflict : VoiceStoreWrite
    data object Unavailable : VoiceStoreWrite
}

/** One encrypted, atomic local snapshot binds the person, event and original source together. */
class VoiceMoneyStore(private val path: File, private val keyProvider: (Boolean) -> SecretKey?) {
    companion object {
        private val lock = Any()
        private val header = byteArrayOf(0x4d, 0x56, 0x01)
        private const val MAX_BYTES = 1_048_576
        private const val MAX_RECORDS = 1000
    }

    fun load(): VoiceStoreRead = synchronized(lock) {
        try { VoiceStoreRead.Ready(read()) } catch (_: Exception) { VoiceStoreRead.Unavailable }
    }

    /** Export is plaintext and must only be called after an explicit user action. */
    fun exportJson(): String? = synchronized(lock) {
        try {
            val snapshot = read()
            JSONObject().put("format", "memora-voice-memory-v1")
                .put("people", JSONArray().apply { snapshot.persons.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
                .put("receipts", JSONArray().apply { snapshot.receipts.forEach { put(JSONObject()
                    .put("id", it.id).put("command", it.commandId).put("person", it.personId)
                    .put("minor", it.minorUnits).put("currency", it.currency).put("source", it.source)
                    .put("recorded", it.recordedAt.toString()).put("occurred", it.occurredAt?.toString() ?: JSONObject.NULL)) } })
                .toString()
        } catch (_: Exception) { null }
    }

    fun save(command: VoiceReceiptCommand): VoiceStoreWrite = synchronized(lock) {
        try {
            val state = read()
            when (val result = VoiceMoneyRules.save(state, command)) {
                is VoiceMemoryChange.Applied -> {
                    if (result.snapshot.receipts.size > MAX_RECORDS || result.snapshot.persons.size > MAX_RECORDS)
                        return@synchronized VoiceStoreWrite.Unavailable
                    write(result.snapshot)
                    VoiceStoreWrite.Saved
                }
                VoiceMemoryChange.Duplicate -> VoiceStoreWrite.Duplicate
                VoiceMemoryChange.Invalid -> VoiceStoreWrite.Invalid
                VoiceMemoryChange.Ambiguous -> VoiceStoreWrite.Ambiguous
                VoiceMemoryChange.Conflict -> VoiceStoreWrite.Conflict
            }
        } catch (_: Exception) { VoiceStoreWrite.Unavailable }
    }

    /** Explicit user deletion; an unmatched ID never changes a different record. */
    fun deleteReceipt(id: String): Boolean = synchronized(lock) {
        try {
            val current = read()
            if (current.receipts.none { it.id == id }) return@synchronized false
            val remaining = current.receipts.filterNot { it.id == id }
            val referenced = remaining.map { it.personId }.toSet()
            write(current.copy(persons = current.persons.filter { it.id in referenced }, receipts = remaining))
            true
        } catch (_: Exception) { false }
    }

    private fun read(): VoiceMoneySnapshot {
        if (!path.exists()) return VoiceMoneySnapshot()
        require(path.length() in (header.size + 28L)..MAX_BYTES.toLong())
        val data = path.readBytes()
        require(data.copyOfRange(0, header.size).contentEquals(header))
        val key = keyProvider(false) ?: error("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(header)
        val json = JSONObject(cipher.doFinal(data.copyOfRange(header.size + 12, data.size)).toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1)
        val people = json.getJSONArray("people")
        val events = json.getJSONArray("receipts")
        require(people.length() <= MAX_RECORDS && events.length() <= MAX_RECORDS)
        val persons = (0 until people.length()).map { index ->
            val item = people.getJSONObject(index)
            VoicePerson(item.getString("id"), item.getString("name"))
        }
        val receipts = (0 until events.length()).map { index ->
            val item = events.getJSONObject(index)
            VoiceReceipt(item.getString("id"), item.getString("command"), item.getString("person"),
                item.getLong("minor"), item.getString("currency"), item.getString("source"),
                Instant.parse(item.getString("recorded")), if (item.isNull("occurred")) null else Instant.parse(item.getString("occurred")))
        }
        require(persons.all { it.id.isNotBlank() && it.name.isNotBlank() } && persons.map { it.id }.toSet().size == persons.size)
        require(receipts.map { it.id }.toSet().size == receipts.size && receipts.map { it.commandId }.toSet().size == receipts.size)
        require(receipts.all { it.id.isNotBlank() && it.commandId.isNotBlank() && it.personId in persons.map(VoicePerson::id) &&
            it.minorUnits > 0 && it.currency == "AZN" && it.source.isNotBlank() && it.source.length <= 20_000 })
        return VoiceMoneySnapshot(persons, receipts)
    }

    private fun write(snapshot: VoiceMoneySnapshot) {
        val people = JSONArray().apply { snapshot.persons.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }
        val receipts = JSONArray().apply { snapshot.receipts.forEach { receipt -> put(JSONObject()
            .put("id", receipt.id).put("command", receipt.commandId).put("person", receipt.personId)
            .put("minor", receipt.minorUnits).put("currency", receipt.currency).put("source", receipt.source)
            .put("recorded", receipt.recordedAt.toString()).put("occurred", receipt.occurredAt?.toString() ?: JSONObject.NULL)) } }
        val text = JSONObject().put("schema", 1).put("people", people).put("receipts", receipts).toString()
        val key = keyProvider(!path.exists()) ?: error("key unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        val payload = header + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12 && payload.size <= MAX_BYTES)
        val temporary = File(path.path + ".new")
        try {
            FileOutputStream(temporary).use { it.write(payload); it.fd.sync() }
            Files.move(temporary.toPath(), path.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

package ai.memora.app.data

import ai.memora.app.domain.*
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedTaskStoreTest {
    private lateinit var file: File
    private lateinit var key: SecretKey
    private lateinit var store: EncryptedTaskStore
    private fun created(id: String = "t1", command: String = "c1", title: String = "Synthetic task"): TaskEvent =
        (TaskRules.create(id, "personal", "owner", command, title) as TaskDecision.Changed).event

    @Before fun setup() {
        file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "test-${java.util.UUID.randomUUID()}.bin")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store = EncryptedTaskStore(file) { key }
    }

    @Test fun encryptedEventsSurviveNewStoreInstance() {
        assertEquals(StoreWrite.Recorded, store.append(created()))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Synthetic task"))
        val loaded = EncryptedTaskStore(file) { key }.load() as StoreRead.Ready
        assertEquals("Synthetic task", loaded.tasks.single().title)
        assertEquals(1L, loaded.tasks.single().version)
    }

    @Test fun repeatedCommandIsIdempotentAndCollisionIsRejected() {
        val event = created()
        assertEquals(StoreWrite.Recorded, store.append(event))
        assertEquals(StoreWrite.Duplicate, store.append(event))
        assertEquals(StoreWrite.Conflict, store.append(created(title = "Different")))
        assertEquals(StoreWrite.Recorded, store.append(created("t2", "c2")))
        assertEquals(2, (store.load() as StoreRead.Ready).tasks.size)
    }

    @Test fun staleEventDoesNotOverwriteSavedData() {
        store.append(created())
        val current = (store.load() as StoreRead.Ready).tasks.single()
        val event = (TaskRules.rename(current, 1, "owner", "c2", "Renamed") as TaskDecision.Changed).event
        assertEquals(StoreWrite.Recorded, store.append(event))
        val bytes = file.readBytes()
        val stale = (TaskRules.complete(current, 1, "owner", "c3", true) as TaskDecision.Changed).event
        assertEquals(StoreWrite.Conflict, store.append(stale))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun damagedCiphertextAndWrongKeyFailClosed() {
        store.append(created())
        val bytes = file.readBytes()
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrongStore = EncryptedTaskStore(file) { wrongKey }
        assertEquals(StoreRead.Unavailable, wrongStore.load())
        assertEquals(StoreWrite.Unavailable, wrongStore.append(created("t2", "c2")))
        assertArrayEquals(bytes, file.readBytes())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(StoreRead.Unavailable, store.load())
        assertEquals(StoreWrite.Unavailable, store.append(created("t2", "c2")))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun missingKeyForExistingDataCannotCauseOverwrite() {
        store.append(created())
        val bytes = file.readBytes()
        val missing = EncryptedTaskStore(file) { create -> if (create) key else null }
        assertEquals(StoreRead.Unavailable, missing.load())
        assertEquals(StoreWrite.Unavailable, missing.append(created("t2", "c2")))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun editingAndDeletionSurviveRestartWithoutResurrectingOldCommands() {
        val original = created()
        assertEquals(StoreWrite.Recorded, store.append(original))
        assertEquals(StoreWrite.Recorded, store.append(created("t2", "c2", "Other")))
        val task = (store.load() as StoreRead.Ready).tasks.first { it.id == "t1" }
        val renamed = (TaskRules.rename(task, task.version, "owner", "rename", "New title") as TaskDecision.Changed).event
        assertEquals(StoreWrite.Recorded, store.append(renamed))
        val afterRename = (EncryptedTaskStore(file) { key }.load() as StoreRead.Ready).tasks.first { it.id == "t1" }
        assertEquals("New title", afterRename.title)
        assertEquals(StoreWrite.Conflict, store.deleteTask("t1", 1))
        assertEquals(StoreWrite.Recorded, store.deleteTask("t1", 2))
        assertEquals(StoreWrite.Conflict, store.append(original))
        val after = (EncryptedTaskStore(file) { key }.load() as StoreRead.Ready).tasks
        assertEquals(listOf("Other"), after.map { it.title })
        val export = (store.export() as StoreExport.Ready).json
        assertFalse(export.contains("Synthetic task"))
        assertFalse(export.contains("New title"))
        assertTrue(export.contains("Other"))
    }

    @Test fun exportContainsHistoryAndClearAllRemovesContent() {
        val original = created()
        store.append(original)
        val current = (store.load() as StoreRead.Ready).tasks.single()
        store.append((TaskRules.rename(current, 1, "owner", "c2", "New title") as TaskDecision.Changed).event)
        val export = (store.export() as StoreExport.Ready).json
        assertTrue(export.contains("Synthetic task"))
        assertTrue(export.contains("New title"))
        assertTrue(export.contains("schema"))
        assertEquals(StoreWrite.Recorded, store.clearAll())
        assertTrue((store.load() as StoreRead.Ready).tasks.isEmpty())
        assertFalse((store.export() as StoreExport.Ready).json.contains("New title"))
        assertEquals(StoreWrite.Conflict, store.append(original))
    }

    @Test fun corruptedStorageCannotBeExportedOrCleared() {
        store.append(created())
        val bytes = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertEquals(StoreExport.Unavailable, store.export())
        assertEquals(StoreWrite.Unavailable, store.clearAll())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun deadlineAndLabelsPersistAcrossRestartAndExport() {
        store.append(created())
        val task = (store.load() as StoreRead.Ready).tasks.single()
        val event = (TaskRules.edit(task, 1, "owner", "edit", "Revised", "2030-10-31T09:00", "Asia/Baku", true, "Sam", "Project") as TaskDecision.Changed).event
        assertEquals(StoreWrite.Recorded, store.append(event))
        val loaded = (EncryptedTaskStore(file) { key }.load() as StoreRead.Ready).tasks.single()
        assertEquals("2030-10-31T09:00", loaded.dueLocal)
        assertEquals("Asia/Baku", loaded.dueZone)
        assertEquals("Sam", loaded.personLabel)
        assertTrue(loaded.remind)
        assertTrue((store.export() as StoreExport.Ready).json.contains("Project"))
    }

    @Test fun legacyEventArrayMigratesWithoutLosingTasks() {
        val legacy = """[{"id":"old","scope":"personal","actor":"owner","command":"old-create","type":"created","title":"Old synthetic"}]"""
        val header = byteArrayOf(0x4d, 0x54, 0x01)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        file.writeBytes(header + cipher.iv + cipher.doFinal(legacy.toByteArray(Charsets.UTF_8)))
        assertEquals("Old synthetic", (store.load() as StoreRead.Ready).tasks.single().title)
        assertEquals(StoreWrite.Recorded, store.append(created("new", "new-create")))
        assertEquals(2, (EncryptedTaskStore(file) { key }.load() as StoreRead.Ready).tasks.size)
    }
}

package ai.memora.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Cipher
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedCaptureStoreTest {
    private lateinit var file: File
    private lateinit var key: SecretKey
    private lateinit var store: EncryptedCaptureStore
    private val time = Instant.parse("2026-01-01T12:00:00Z")

    @Before fun setup() {
        file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "capture-${UUID.randomUUID()}.bin")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store = EncryptedCaptureStore(file) { key }
    }

    private fun submit(text: String = "Synthetic note", command: String = "c1", source: String = "s1", revision: String = "r1") =
        store.submit(command, 1, source, revision, text, time, "Asia/Baku")

    @Test fun draftAndCaptureSurviveRestartAndAreEncrypted() {
        assertEquals(CaptureWrite.Recorded, store.saveDraft(0, "Synthetic note"))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Synthetic note"))
        val restarted = EncryptedCaptureStore(file) { key }
        assertEquals("Synthetic note", (restarted.load() as CaptureRead.Ready).snapshot.draft)
        assertEquals(CaptureWrite.Recorded, submit())
        val snapshot = (restarted.load() as CaptureRead.Ready).snapshot
        assertEquals("", snapshot.draft)
        assertEquals(2L, snapshot.draftVersion)
        assertEquals("Synthetic note", snapshot.captures.single().source.revisions.single().text)
        assertEquals("Asia/Baku", snapshot.captures.single().zone)
    }

    @Test fun retryIsIdempotentAndDifferentRequestWithSameCommandConflicts() {
        store.saveDraft(0, "Synthetic note")
        assertEquals(CaptureWrite.Recorded, submit())
        assertEquals(CaptureWrite.Duplicate, submit())
        val before = file.readBytes()
        assertEquals(CaptureWrite.Conflict, submit(text = "Different"))
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, (store.load() as CaptureRead.Ready).snapshot.captures.size)
    }

    @Test fun staleDraftAndIncorrectSubmitDoNotLoseNewerText() {
        store.saveDraft(0, "first")
        store.saveDraft(1, "second")
        val before = file.readBytes()
        assertEquals(CaptureWrite.Conflict, store.saveDraft(1, "stale"))
        assertEquals(CaptureWrite.Conflict, submit(text = "first"))
        assertArrayEquals(before, file.readBytes())
        assertEquals("second", (store.load() as CaptureRead.Ready).snapshot.draft)
    }

    @Test fun corruptionOrMissingKeyNeverOverwritesExistingFile() {
        store.saveDraft(0, "Synthetic note")
        val original = file.readBytes()
        val missing = EncryptedCaptureStore(file) { create -> if (create) key else null }
        assertEquals(CaptureRead.Unavailable, missing.load())
        assertEquals(CaptureWrite.Unavailable, missing.saveDraft(1, "other"))
        assertArrayEquals(original, file.readBytes())
        original[original.lastIndex] = (original.last().toInt() xor 1).toByte()
        file.writeBytes(original)
        assertEquals(CaptureRead.Unavailable, store.load())
        assertEquals(CaptureWrite.Unavailable, store.saveDraft(1, "other"))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun invalidTextAndZoneAreRejectedWithoutChangingDraft() {
        assertEquals(CaptureWrite.Invalid, store.saveDraft(0, "\uD800"))
        assertEquals(CaptureWrite.Recorded, store.saveDraft(0, "Synthetic note"))
        assertEquals(CaptureWrite.Invalid, store.submit("c", 1, "s", "r", "Synthetic note", time, "wrong/zone"))
        assertEquals(CaptureWrite.Invalid, store.submit("c", 1, "s", "r", " ", time, "UTC"))
        assertEquals("Synthetic note", (store.load() as CaptureRead.Ready).snapshot.draft)
    }

    @Test fun identicalTextWithDifferentCommandsAndSourcesProducesTwoCaptures() {
        store.saveDraft(0, "Synthetic note")
        assertEquals(CaptureWrite.Recorded, submit())
        assertEquals(CaptureWrite.Recorded, store.saveDraft(2, "Synthetic note"))
        assertEquals(CaptureWrite.Recorded, store.submit("c2", 3, "s2", "r2", "Synthetic note", time, "UTC"))
        assertEquals(2, (store.load() as CaptureRead.Ready).snapshot.captures.size)
    }

    @Test fun concurrentWritersWithSameExpectedVersionCannotOverwriteEachOther() {
        val ready = CountDownLatch(2)
        val begin = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf("first", "second").map { text ->
                executor.submit<CaptureWrite> {
                    val writer = EncryptedCaptureStore(file) { key }
                    ready.countDown()
                    check(begin.await(5, TimeUnit.SECONDS))
                    writer.saveDraft(0, text)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            begin.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == CaptureWrite.Recorded })
            assertEquals(1, results.count { it == CaptureWrite.Conflict })
            val stored = (store.load() as CaptureRead.Ready).snapshot
            assertTrue(stored.draft in listOf("first", "second"))
            assertEquals(1L, stored.draftVersion)
        } finally {
            begin.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun rejectedOversizedOrDuplicateIdentifiersLeavePersistedSnapshotUntouched() {
        assertEquals(CaptureWrite.Invalid, store.saveDraft(0, "x".repeat(20_001)))
        store.saveDraft(0, "Synthetic note")
        assertEquals(CaptureWrite.Recorded, submit())
        store.saveDraft(2, "another")
        val before = file.readBytes()
        assertEquals(CaptureWrite.Conflict, store.submit("c2", 3, "s1", "r2", "another", time, "UTC"))
        assertEquals(CaptureWrite.Conflict, store.submit("c2", 3, "s2", "r1", "another", time, "UTC"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun failedTemporaryWritePreservesOldDraftAndDoesNotClaimSuccess() {
        assertEquals(CaptureWrite.Recorded, store.saveDraft(0, "first"))
        val original = file.readBytes()
        val temporary = File(file.path + ".new")
        assertTrue(temporary.mkdir())
        assertEquals(CaptureWrite.Unavailable, store.saveDraft(1, "second"))
        assertArrayEquals(original, file.readBytes())
        assertEquals("first", (store.load() as CaptureRead.Ready).snapshot.draft)
    }

    @Test fun deletionRemovesSourceAndExportButBlocksOldCommandAfterRestart() {
        store.saveDraft(0, "Synthetic note")
        assertEquals(CaptureWrite.Recorded, submit())
        assertEquals(CaptureWrite.Conflict, store.delete("s1", "wrong"))
        assertEquals(CaptureWrite.Recorded, store.delete("s1", "r1"))
        val restarted = EncryptedCaptureStore(file) { key }
        val snapshot = (restarted.load() as CaptureRead.Ready).snapshot
        assertTrue(snapshot.captures.isEmpty())
        assertEquals(1, snapshot.tombstones.size)
        assertFalse((restarted.export() as CaptureExport.Ready).json.contains("Synthetic note"))
        assertEquals(CaptureWrite.Duplicate, restarted.delete("s1", "r1"))
        assertEquals(CaptureWrite.Conflict, restarted.delete("s1", "other"))
        assertEquals(CaptureWrite.Conflict, submit())
        assertEquals(CaptureWrite.Recorded, restarted.saveDraft(2, "new"))
        assertEquals(CaptureWrite.Conflict, restarted.submit("c1", 3, "s2", "r2", "new", time, "UTC"))
        assertEquals(CaptureWrite.Conflict, restarted.submit("c2", 3, "s1", "r2", "new", time, "UTC"))
        assertEquals(CaptureWrite.Conflict, restarted.submit("c2", 3, "s2", "r1", "new", time, "UTC"))
    }

    @Test fun exportIncludesDraftAndCaptureMetadataWithoutTombstones() {
        store.saveDraft(0, "Synthetic note")
        submit()
        store.saveDraft(2, "Synthetic draft")
        val obj = JSONObject((store.export() as CaptureExport.Ready).json)
        assertEquals("memora-captures", obj.getString("format"))
        assertEquals(2, obj.getInt("schema"))
        assertEquals("Synthetic draft", obj.getString("draft"))
        val entry = obj.getJSONArray("captures").getJSONObject(0)
        assertEquals("Synthetic note", entry.getString("text"))
        assertEquals("Asia/Baku", entry.getString("zone"))
        assertEquals(time.toString(), entry.getString("recordedAt"))
        assertFalse(obj.has("deleted"))
    }

    @Test fun legacySchemaCanBeReadAndRewrittenWithoutLosingDraft() {
        val old = """{"schema":1,"draft":"legacy synthetic","draftVersion":1,"captures":[]}"""
        val header = byteArrayOf(0x4d, 0x43, 0x01)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        file.writeBytes(header + cipher.iv + cipher.doFinal(old.toByteArray(Charsets.UTF_8)))
        assertEquals("legacy synthetic", (store.load() as CaptureRead.Ready).snapshot.draft)
        assertEquals(CaptureWrite.Recorded, store.saveDraft(1, "updated synthetic"))
        assertEquals("updated synthetic", (EncryptedCaptureStore(file) { key }.load() as CaptureRead.Ready).snapshot.draft)
    }

    @Test fun fullTombstoneSetRejectsNewSubmissionWithoutClearingDraft() {
        val deleted = JSONArray()
        repeat(500) { deleted.put(JSONObject().put("command", "c$it").put("source", "s$it").put("revision", "r$it")) }
        val plain = JSONObject().put("schema", 2).put("draft", "synthetic")
            .put("draftVersion", 1).put("captures", JSONArray()).put("deleted", deleted).toString()
        val header = byteArrayOf(0x4d, 0x43, 0x01)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        file.writeBytes(header + cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        val before = file.readBytes()
        assertEquals(CaptureWrite.LimitReached, store.submit("new-c", 1, "new-s", "new-r", "synthetic", time, "UTC"))
        assertArrayEquals(before, file.readBytes())
        assertEquals("synthetic", (store.load() as CaptureRead.Ready).snapshot.draft)
    }

    @Test fun batchArchiveIsAtomicAndVersionedAcrossRestart() {
        store.saveDraft(0, "first")
        store.submit("c1", 1, "s1", "r1", "first", time, "UTC")
        store.saveDraft(2, "second")
        store.submit("c2", 3, "s2", "r2", "second", time, "UTC")
        val selected = listOf(CaptureArchiveSelection("s1", "r1", 0), CaptureArchiveSelection("s2", "r2", 0))
        val before = file.readBytes()
        assertEquals(CaptureWrite.Conflict, store.archive(listOf(selected[0], selected[1].copy(organizationVersion = 9)), true))
        assertArrayEquals(before, file.readBytes())
        assertEquals(CaptureWrite.Recorded, store.archive(selected, true))
        val reopened = EncryptedCaptureStore(file) { key }
        assertTrue((reopened.load() as CaptureRead.Ready).snapshot.captures.all { it.archived && it.organizationVersion == 1L })
        assertEquals(CaptureWrite.Conflict, reopened.archive(selected, false))
        assertEquals(CaptureWrite.Duplicate, reopened.submit("c1", 1, "s1", "r1", "first", time, "UTC"))
        val newer = selected.map { it.copy(organizationVersion = 1) }
        assertEquals(CaptureWrite.Recorded, reopened.archive(newer, false))
        assertEquals(CaptureWrite.Conflict, reopened.archive(selected, true))
        assertTrue((reopened.load() as CaptureRead.Ready).snapshot.captures.all { !it.archived && it.organizationVersion == 2L })
    }

    @Test fun archiveRejectsDuplicateSelectionsAndDeletedEntries() {
        store.saveDraft(0, "first")
        store.submit("c1", 1, "s1", "r1", "first", time, "UTC")
        val selected = CaptureArchiveSelection("s1", "r1", 0)
        assertEquals(CaptureWrite.Invalid, store.archive(listOf(selected, selected), true))
        assertEquals(CaptureWrite.Recorded, store.delete("s1", "r1"))
        assertEquals(CaptureWrite.Conflict, store.archive(listOf(selected), true))
    }
}

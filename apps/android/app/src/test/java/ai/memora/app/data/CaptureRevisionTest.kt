package ai.memora.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.domain.TextEvidence
import ai.memora.app.domain.TextSourceRules
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.Cipher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureRevisionTest {
    private lateinit var file: File
    private lateinit var key: SecretKey
    private lateinit var store: EncryptedCaptureStore
    private val source = "00000000-0000-4000-8000-000000000001"
    private val first = "00000000-0000-4000-8000-000000000002"
    private val second = "00000000-0000-4000-8000-000000000003"
    private val third = "00000000-0000-4000-8000-000000000004"
    private val time = Instant.parse("2026-01-01T12:00:00Z")

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "revisions-${UUID.randomUUID()}.bin")
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store = EncryptedCaptureStore(file) { key }
        assertEquals(CaptureWrite.Recorded, store.saveDraft(0, "Salam synthetic"))
        assertEquals(CaptureWrite.Recorded, store.submit("command", 1, source, first, "Salam synthetic", time, "Asia/Baku"))
    }

    @Test fun versionHistorySurvivesRestartAndOriginalEvidenceStillResolves() {
        assertEquals(CaptureWrite.Recorded, store.revise(source, first, second, "Salam corrected", time.plusSeconds(5)))
        val reopened = EncryptedCaptureStore(file) { key }
        val text = (reopened.load() as CaptureRead.Ready).snapshot.captures.single().source
        assertEquals(listOf(1L, 2L), text.revisions.map { it.number })
        assertEquals("Salam", TextSourceRules.quote(text, TextEvidence(first, 0, 5)))
        assertEquals("corrected", TextSourceRules.quote(text, TextEvidence(second, 6, 15)))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("Salam corrected"))
        assertEquals(CaptureWrite.Duplicate, reopened.submit("command", 1, source, first, "Salam synthetic", time, "Asia/Baku"))
    }

    @Test fun revisionsAreIdempotentAndStaleOrReusedIdsConflict() {
        val at = time.plusSeconds(2)
        assertEquals(CaptureWrite.Recorded, store.revise(source, first, second, "Second", at))
        assertEquals(CaptureWrite.Duplicate, store.revise(source, first, second, "Second", at))
        assertEquals(CaptureWrite.Conflict, store.revise(source, first, second, "Different", at))
        assertEquals(CaptureWrite.Conflict, store.revise(source, first, third, "Stale", at))
        assertEquals(CaptureWrite.Recorded, store.revise(source, second, third, "Third", at.plusSeconds(1)))
        assertEquals(CaptureWrite.Duplicate, store.revise(source, first, second, "Second", at))
        assertEquals(3, (store.load() as CaptureRead.Ready).snapshot.captures.single().source.revisions.size)
    }

    @Test fun versionedExportRoundTripsAllHistoryAndDeletedVersionsStayBlocked() {
        store.revise(source, first, second, "Second", time.plusSeconds(1))
        val json = (store.export() as CaptureExport.Ready).json
        assertEquals(3, JSONObject(json).getInt("schema"))
        val target = EncryptedCaptureStore(File(file.parentFile, "target-${UUID.randomUUID()}.bin")) { key }
        val plan = target.previewImport(json) as CaptureImportPreview.Ready
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, plan.ticket))
        val restored = (target.load() as CaptureRead.Ready).snapshot.captures.single().source
        assertEquals(listOf(first, second), restored.revisions.map { it.id })
        assertEquals(CaptureWrite.Recorded, target.delete(source, second))
        assertEquals(CaptureImportPreview.Conflict, target.previewImport(json))
        assertEquals(CaptureWrite.Conflict, target.submit("another", 0, "new source", first, "other", time, "UTC"))
    }

    @Test fun importRejectsDuplicateVersionAndDoesNotWriteAnything() {
        store.revise(source, first, second, "Second", time.plusSeconds(1))
        val json = JSONObject((store.export() as CaptureExport.Ready).json)
        val history = json.getJSONArray("captures").getJSONObject(0).getJSONArray("revisions")
        history.getJSONObject(1).put("id", first)
        val targetFile = File(file.parentFile, "invalid-${UUID.randomUUID()}.bin")
        val target = EncryptedCaptureStore(targetFile) { key }
        assertEquals(CaptureImportPreview.Invalid, target.previewImport(json.toString()))
        assertFalse(targetFile.exists())
    }

    @Test fun legacyV4EntryUpgradesWithoutChangingItsOriginalVersion() {
        val entry = JSONObject().put("command", "command").put("draftVersion", 1).put("source", source)
            .put("revision", first).put("text", "Legacy synthetic").put("recordedAt", time.toString())
            .put("zone", "UTC").put("origin", "LOCAL").put("archived", true).put("organizationVersion", 2)
        val plain = JSONObject().put("schema", 4).put("draft", "").put("draftVersion", 2)
            .put("captures", JSONArray().put(entry)).put("deleted", JSONArray()).toString()
        val header = byteArrayOf(0x4d, 0x43, 0x01)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(header)
        file.writeBytes(header + cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        assertEquals(CaptureWrite.Recorded, store.revise(source, first, second, "Updated synthetic", time.plusSeconds(1)))
        val restored = (EncryptedCaptureStore(file) { key }.load() as CaptureRead.Ready).snapshot.captures.single()
        assertTrue(restored.archived)
        assertEquals(2L, restored.organizationVersion)
        assertEquals(listOf("Legacy synthetic", "Updated synthetic"), restored.source.revisions.map { it.text })
    }

    @Test fun allLegacySchemasPreserveSourceWhenAddingRevision() {
        for (schema in 1..4) {
            val legacyFile = File(file.parentFile, "legacy-$schema-${UUID.randomUUID()}.bin")
            val entry = JSONObject().put("command", "command").put("draftVersion", 1).put("source", source)
                .put("revision", first).put("text", "Legacy synthetic").put("recordedAt", time.toString()).put("zone", "UTC")
            if (schema >= 3) entry.put("origin", "LOCAL")
            if (schema >= 4) entry.put("archived", true).put("organizationVersion", 2)
            val plain = JSONObject().put("schema", schema).put("draft", "").put("draftVersion", 2)
                .put("captures", JSONArray().put(entry))
            if (schema >= 2) plain.put("deleted", JSONArray())
            val header = byteArrayOf(0x4d, 0x43, 0x01)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(header)
            legacyFile.writeBytes(header + cipher.iv + cipher.doFinal(plain.toString().toByteArray(Charsets.UTF_8)))
            val legacyStore = EncryptedCaptureStore(legacyFile) { key }
            assertEquals("schema $schema", CaptureWrite.Recorded, legacyStore.revise(source, first, second, "Corrected synthetic", time.plusSeconds(1)))
            val restored = (EncryptedCaptureStore(legacyFile) { key }.load() as CaptureRead.Ready).snapshot.captures.single()
            assertEquals(listOf(first, second), restored.source.revisions.map { it.id })
            assertEquals(listOf("Legacy synthetic", "Corrected synthetic"), restored.source.revisions.map { it.text })
            assertEquals(schema >= 4, restored.archived)
        }
    }
}

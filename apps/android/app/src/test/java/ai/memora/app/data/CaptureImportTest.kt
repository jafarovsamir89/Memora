package ai.memora.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.KeyGenerator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureImportTest {
    private lateinit var source: EncryptedCaptureStore
    private lateinit var target: EncryptedCaptureStore
    private lateinit var targetFile: File
    private val time = Instant.parse("2026-01-01T12:00:00Z")
    private val sourceId = "00000000-0000-4000-8000-000000000001"
    private val revisionId = "00000000-0000-4000-8000-000000000002"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        source = EncryptedCaptureStore(File(context.cacheDir, "source-${UUID.randomUUID()}.bin")) { key }
        targetFile = File(context.cacheDir, "target-${UUID.randomUUID()}.bin")
        target = EncryptedCaptureStore(targetFile) { key }
        assertEquals(CaptureWrite.Recorded, source.saveDraft(0, "synthetic saved"))
        assertEquals(CaptureWrite.Recorded, source.submit("c1", 1, sourceId, revisionId, "synthetic saved", time, "Asia/Baku"))
        assertEquals(CaptureWrite.Recorded, source.saveDraft(2, "synthetic draft"))
    }

    private fun exported() = (source.export() as CaptureExport.Ready).json
    private fun preview(json: String) = target.previewImport(json) as CaptureImportPreview.Ready

    @Test fun roundTripRestoresTextAndProvenanceWithoutInventingDraftVersion() {
        val json = exported()
        val plan = preview(json)
        assertEquals(1, plan.newSources)
        assertTrue(plan.restoresDraft)
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, plan.ticket))
        val state = (target.load() as CaptureRead.Ready).snapshot
        assertEquals("synthetic draft", state.draft)
        assertEquals("synthetic saved", state.captures.single().source.revisions.single().text)
        assertEquals(time, state.captures.single().source.revisions.single().recordedAt)
        assertEquals("Asia/Baku", state.captures.single().zone)
        assertEquals(CaptureOrigin.IMPORTED, state.captures.single().origin)
        assertNull(state.captures.single().draftVersion)
        val second = preview(json)
        assertEquals(0, second.newSources)
        assertEquals(1, second.existingSources)
        assertEquals(CaptureWrite.Unchanged, target.applyImport(json, second.ticket))
    }

    @Test fun localDraftConflictNeverOverwritesEitherSide() {
        target.saveDraft(0, "other synthetic draft")
        val before = targetFile.readBytes()
        assertEquals(CaptureImportPreview.Conflict, target.previewImport(exported()))
        assertArrayEquals(before, targetFile.readBytes())
        assertEquals("other synthetic draft", (target.load() as CaptureRead.Ready).snapshot.draft)
    }

    @Test fun deletedSourceCannotBeResurrectedFromOldExport() {
        val json = exported()
        val plan = preview(json)
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, plan.ticket))
        assertEquals(CaptureWrite.Recorded, target.delete(sourceId, revisionId))
        assertEquals(CaptureImportPreview.Conflict, target.previewImport(json))
        assertTrue((target.load() as CaptureRead.Ready).snapshot.captures.isEmpty())
    }

    @Test fun changeAfterPreviewOrChangedFileRequiresNewPreview() {
        val json = exported()
        val plan = preview(json)
        target.saveDraft(0, "another")
        val before = targetFile.readBytes()
        assertEquals(CaptureWrite.Conflict, target.applyImport(json, plan.ticket))
        assertArrayEquals(before, targetFile.readBytes())
        val newTarget = EncryptedCaptureStore(File(targetFile.parentFile, "fresh-${UUID.randomUUID()}.bin")) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        val ready = newTarget.previewImport(json) as CaptureImportPreview.Ready
        assertEquals(CaptureWrite.Conflict, newTarget.applyImport(json + " ", ready.ticket))
    }

    @Test fun malformedDuplicateOrOversizedFilesFailClosed() {
        val json = exported()
        assertEquals(CaptureImportPreview.Invalid, target.previewImport(json + "junk"))
        assertEquals(CaptureImportPreview.Invalid, target.previewImport("""{"format":"memora-captures","format":"memora-captures","schema":1,"draft":"","captures":[]}"""))
        assertEquals(CaptureImportPreview.LimitReached, target.previewImport(" ".repeat(CaptureImportReader.MAX_BYTES + 1)))
        val root = JSONObject(json)
        root.getJSONArray("captures").put(root.getJSONArray("captures").getJSONObject(0))
        assertEquals(CaptureImportPreview.Invalid, target.previewImport(root.toString()))
        assertFalse(targetFile.exists())
    }

    @Test fun sameSourceWithChangedContentsIsConflict() {
        val json = exported()
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, preview(json).ticket))
        val changed = JSONObject(json)
        changed.getJSONArray("captures").getJSONObject(0).put("text", "different synthetic")
        assertEquals(CaptureImportPreview.Conflict, target.previewImport(changed.toString()))
    }

    @Test fun oneConflictingSourceRejectsWholeBatchWithoutAddingOthers() {
        val json = exported()
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, preview(json).ticket))
        val before = targetFile.readBytes()
        val batch = JSONObject(json)
        batch.getJSONArray("captures").getJSONObject(0).put("text", "conflicting synthetic")
        batch.getJSONArray("captures").put(JSONObject().put("id", "00000000-0000-4000-8000-000000000003")
            .put("revision", "00000000-0000-4000-8000-000000000004").put("text", "other synthetic")
            .put("recordedAt", time.toString()).put("zone", "UTC").put("archived", false))
        assertEquals(CaptureImportPreview.Conflict, target.previewImport(batch.toString()))
        assertArrayEquals(before, targetFile.readBytes())
        assertEquals(1, (target.load() as CaptureRead.Ready).snapshot.captures.size)
    }

    @Test fun archiveStatusRoundTripsButExistingLocalChoiceIsNotOverwritten() {
        assertEquals(CaptureWrite.Recorded, source.archive(listOf(CaptureArchiveSelection(sourceId, revisionId, 0)), true))
        val json = exported()
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, preview(json).ticket))
        val restored = (target.load() as CaptureRead.Ready).snapshot.captures.single()
        assertTrue(restored.archived)
        assertEquals(0L, restored.organizationVersion)
        assertEquals(CaptureWrite.Recorded, target.archive(listOf(CaptureArchiveSelection(sourceId, revisionId, 0)), false))
        val next = preview(json)
        assertEquals(1, next.existingSources)
        assertEquals(CaptureWrite.Unchanged, target.applyImport(json, next.ticket))
        assertFalse((target.load() as CaptureRead.Ready).snapshot.captures.single().archived)
    }

    @Test fun legacyExportWithoutArchiveRemainsSupported() {
        val old = JSONObject(exported()).put("schema", 1)
        old.getJSONArray("captures").getJSONObject(0).remove("archived")
        val plan = target.previewImport(old.toString()) as CaptureImportPreview.Ready
        assertEquals(CaptureWrite.Recorded, target.applyImport(old.toString(), plan.ticket))
        assertFalse((target.load() as CaptureRead.Ready).snapshot.captures.single().archived)
    }

    @Test fun archiveChangeAfterPreviewRequiresNewPreview() {
        val json = exported()
        assertEquals(CaptureWrite.Recorded, target.applyImport(json, preview(json).ticket))
        val old = preview(json)
        assertEquals(CaptureWrite.Recorded, target.archive(listOf(CaptureArchiveSelection(sourceId, revisionId, 0)), true))
        assertEquals(CaptureWrite.Conflict, target.applyImport(json, old.ticket))
        assertTrue((target.load() as CaptureRead.Ready).snapshot.captures.single().archived)
    }
}

package ai.memora.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureSessionTest {
    private val ids = CaptureSubmissionIds("command", "source", "revision", Instant.parse("2026-01-01T12:00:00Z"), "UTC")

    private fun persistence(): CapturePersistence {
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "session-${UUID.randomUUID()}.bin")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return LocalCapturePersistence(EncryptedCaptureStore(file) { key })
    }

    @Test fun editsAutosaveAndSubmissionPersistsExactlyOnce() = runBlocking {
        val disk = persistence()
        val session = CaptureSession(disk, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("one")
            session.edit("one two")
            withTimeout(5_000) { session.state.first { it.savedDraft == "one two" && !it.saving } }
            session.submit(ids)
            val result = withTimeout(5_000) { session.state.first { it.captures.size == 1 && !it.submitting } }
            assertEquals("", result.draft)
            assertEquals("one two", result.captures.single().source.revisions.single().text)
            assertEquals(1, (disk.load() as CaptureRead.Ready).snapshot.captures.size)
        } finally { session.close() }
    }

    @Test fun unknownSubmissionResultRetriesOriginalOperationAndBlocksNewEdits() = runBlocking {
        val disk = persistence()
        var calls = 0
        val unreliable = object : CapturePersistence by disk {
            override fun submit(commandId: String, expectedVersion: Long, sourceId: String, revisionId: String,
                text: String, recordedAt: Instant, zone: String): CaptureWrite {
                calls++
                val result = disk.submit(commandId, expectedVersion, sourceId, revisionId, text, recordedAt, zone)
                return if (calls == 1 && result == CaptureWrite.Recorded) CaptureWrite.Unavailable else result
            }
        }
        val session = CaptureSession(unreliable, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            session.submit(ids)
            withTimeout(5_000) { session.state.first { it.retrySubmission && !it.submitting } }
            session.edit("must not replace pending")
            assertEquals("synthetic", session.state.value.draft)
            session.submit(ids.copy(command = "ignored new command"))
            val result = withTimeout(5_000) { session.state.first { it.captures.size == 1 && !it.submitting } }
            assertEquals("command", result.captures.single().commandId)
            assertEquals(2, calls)
            assertEquals(1, (disk.load() as CaptureRead.Ready).snapshot.captures.size)
        } finally { session.close() }
    }

    @Test fun rapidEditsWhileDiskBusyEndWithLatestText() = runBlocking {
        val disk = persistence()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : CapturePersistence by disk {
            var first = true
            override fun saveDraft(expectedVersion: Long, text: String): CaptureWrite {
                if (first) {
                    first = false
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return disk.saveDraft(expectedVersion, text)
            }
        }
        val session = CaptureSession(slow, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("first")
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            session.edit("second")
            session.edit("latest")
            release.countDown()
            val result = withTimeout(5_000) { session.state.first { it.savedDraft == "latest" && !it.saving } }
            assertEquals("latest", result.draft)
            assertEquals("latest", (disk.load() as CaptureRead.Ready).snapshot.draft)
        } finally { release.countDown(); session.close() }
    }

    @Test fun failedWriteKeepsVisibleDraftAndRetryPersistsIt() = runBlocking {
        val disk = persistence()
        var fail = true
        val unreliable = object : CapturePersistence by disk {
            override fun saveDraft(expectedVersion: Long, text: String) =
                if (fail) CaptureWrite.Unavailable else disk.saveDraft(expectedVersion, text)
        }
        val session = CaptureSession(unreliable, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            val failed = withTimeout(5_000) { session.state.first { it.error } }
            assertEquals("synthetic", failed.draft)
            assertTrue(failed.dirty)
            fail = false
            session.retrySave()
            withTimeout(5_000) { session.state.first { !it.dirty && !it.error } }
            assertEquals("synthetic", (disk.load() as CaptureRead.Ready).snapshot.draft)
        } finally { session.close() }
    }

    @Test fun initialReadFailureDisablesEditingUntilExplicitRetrySucceeds() = runBlocking {
        val disk = persistence()
        var fail = true
        val unreliable = object : CapturePersistence by disk {
            override fun load() = if (fail) CaptureRead.Unavailable else disk.load()
        }
        val session = CaptureSession(unreliable, this)
        try {
            withTimeout(5_000) { session.state.first { it.error && !it.loading } }
            session.edit("not accepted before load")
            assertEquals("", session.state.value.draft)
            fail = false
            session.retryLoad()
            withTimeout(5_000) { session.state.first { it.ready && !it.loading } }
            session.edit("synthetic")
            withTimeout(5_000) { session.state.first { it.savedDraft == "synthetic" && !it.saving } }
            assertEquals("synthetic", (disk.load() as CaptureRead.Ready).snapshot.draft)
        } finally { session.close() }
    }

    @Test fun unexpectedStorageExceptionLeavesDraftAvailableForRetry() = runBlocking {
        val disk = persistence()
        var fail = true
        val unreliable = object : CapturePersistence by disk {
            override fun saveDraft(expectedVersion: Long, text: String): CaptureWrite {
                if (fail) error("synthetic failure")
                return disk.saveDraft(expectedVersion, text)
            }
        }
        val session = CaptureSession(unreliable, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            withTimeout(5_000) { session.state.first { it.error && it.dirty } }
            fail = false
            session.retrySave()
            withTimeout(5_000) { session.state.first { !it.dirty && !it.error } }
            assertEquals("synthetic", (disk.load() as CaptureRead.Ready).snapshot.draft)
        } finally { session.close() }
    }

    @Test fun unknownSaveResultIsReconciledFromDiskWithoutLosingNewEdits() = runBlocking {
        val disk = persistence()
        var first = true
        val uncertain = object : CapturePersistence by disk {
            override fun saveDraft(expectedVersion: Long, text: String): CaptureWrite {
                val result = disk.saveDraft(expectedVersion, text)
                if (first && result == CaptureWrite.Recorded) {
                    first = false
                    return CaptureWrite.Unavailable
                }
                return result
            }
        }
        val session = CaptureSession(uncertain, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            val result = withTimeout(5_000) { session.state.first { !it.dirty && it.savedDraft == "synthetic" } }
            assertFalse(result.error)
            session.edit("later")
            withTimeout(5_000) { session.state.first { !it.dirty && it.savedDraft == "later" } }
            assertEquals("later", (disk.load() as CaptureRead.Ready).snapshot.draft)
        } finally { session.close() }
    }

    @Test fun uncertainDeleteIsConfirmedByTombstoneWithoutResurrectingEntry() = runBlocking {
        val disk = persistence()
        val uncertain = object : CapturePersistence by disk {
            override fun delete(sourceId: String, expectedRevisionId: String): CaptureWrite {
                val result = disk.delete(sourceId, expectedRevisionId)
                return if (result == CaptureWrite.Recorded) CaptureWrite.Unavailable else result
            }
        }
        val session = CaptureSession(uncertain, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            session.submit(ids)
            withTimeout(5_000) { session.state.first { it.captures.size == 1 && !it.submitting } }
            session.delete(ids.source, ids.revision)
            val deleted = withTimeout(5_000) { session.state.first { it.captures.isEmpty() && !it.managementBusy } }
            assertFalse(deleted.managementError)
            val snapshot = (disk.load() as CaptureRead.Ready).snapshot
            assertTrue(snapshot.captures.isEmpty())
            assertEquals(ids.source, snapshot.tombstones.single().sourceId)
        } finally { session.close() }
    }

    @Test fun failedDeleteDoesNotClaimSuccessOrRemoveVisibleEntry() = runBlocking {
        val disk = persistence()
        val failing = object : CapturePersistence by disk {
            override fun delete(sourceId: String, expectedRevisionId: String) = CaptureWrite.Unavailable
        }
        val session = CaptureSession(failing, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            session.submit(ids)
            withTimeout(5_000) { session.state.first { it.captures.size == 1 && !it.submitting } }
            session.delete(ids.source, ids.revision)
            val result = withTimeout(5_000) { session.state.first { it.managementError && !it.managementBusy } }
            assertEquals(1, result.captures.size)
            assertEquals("synthetic", result.captures.single().source.revisions.single().text)
        } finally { session.close() }
    }

    @Test fun knownCapacityLimitDoesNotLockDraftAsUnknownSubmission() = runBlocking {
        val disk = persistence()
        val limited = object : CapturePersistence by disk {
            override fun submit(commandId: String, expectedVersion: Long, sourceId: String, revisionId: String,
                text: String, recordedAt: Instant, zone: String) = CaptureWrite.LimitReached
        }
        val session = CaptureSession(limited, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            session.submit(ids)
            val limitedState = withTimeout(5_000) { session.state.first { it.limitReached && !it.submitting } }
            assertFalse(limitedState.retrySubmission)
            assertEquals("synthetic", limitedState.draft)
            assertTrue(session.export() is CaptureExport.Ready)
            session.edit("changed")
            withTimeout(5_000) { session.state.first { it.savedDraft == "changed" && !it.saving } }
            Unit
        } finally { session.close() }
    }

    @Test fun importPreviewDoesNotMutateUntilExplicitApply() = runBlocking {
        val source = persistence()
        assertEquals(CaptureWrite.Recorded, source.saveDraft(0, "synthetic import"))
        assertEquals(CaptureWrite.Recorded, source.submit("c1", 1, "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002", "synthetic import", ids.recordedAt, "UTC"))
        val json = (source.export() as CaptureExport.Ready).json
        val target = persistence()
        val session = CaptureSession(target, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            val preview = session.previewImport(json) as CaptureImportPreview.Ready
            assertTrue((target.load() as CaptureRead.Ready).snapshot.captures.isEmpty())
            session.applyImport(json, preview.ticket)
            val result = withTimeout(5_000) { session.state.first { it.importDone && !it.managementBusy } }
            assertEquals(1, result.captures.size)
            assertEquals(CaptureOrigin.IMPORTED, result.captures.single().origin)
        } finally { session.close() }
    }

    @Test fun changedLocalStateAfterImportPreviewIsReportedWithoutOverwrite() = runBlocking {
        val source = persistence()
        source.saveDraft(0, "from export")
        val json = (source.export() as CaptureExport.Ready).json
        val target = persistence()
        val session = CaptureSession(target, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            val preview = session.previewImport(json) as CaptureImportPreview.Ready
            assertEquals(CaptureWrite.Recorded, target.saveDraft(0, "new local draft"))
            session.applyImport(json, preview.ticket)
            val result = withTimeout(5_000) { session.state.first { it.importStale && !it.managementBusy } }
            assertFalse(result.importDone)
            assertEquals("new local draft", result.draft)
            assertEquals("new local draft", (target.load() as CaptureRead.Ready).snapshot.draft)
        } finally { session.close() }
    }

    @Test fun uncertainArchiveResultIsReconciledFromPersistedVersions() = runBlocking {
        val disk = persistence()
        val uncertain = object : CapturePersistence by disk {
            override fun archive(selections: List<CaptureArchiveSelection>, archived: Boolean): CaptureWrite {
                val result = disk.archive(selections, archived)
                return if (result == CaptureWrite.Recorded) CaptureWrite.Unavailable else result
            }
        }
        val session = CaptureSession(uncertain, this)
        try {
            withTimeout(5_000) { session.state.first { it.ready } }
            session.edit("synthetic")
            session.submit(ids)
            withTimeout(5_000) { session.state.first { it.captures.size == 1 && !it.submitting } }
            session.archive(listOf(CaptureArchiveSelection(ids.source, ids.revision, 0)), true)
            val archived = withTimeout(5_000) { session.state.first { !it.managementBusy && it.captures.singleOrNull()?.archived == true } }
            assertFalse(archived.managementError)
            assertEquals(1L, archived.captures.single().organizationVersion)
        } finally { session.close() }
    }
}

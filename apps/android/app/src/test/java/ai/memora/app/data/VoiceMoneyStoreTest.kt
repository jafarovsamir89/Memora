package ai.memora.app.data

import ai.memora.app.domain.*
import java.io.File
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceMoneyStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun command() = VoiceReceiptCommand("cmd", "person", "receipt", "Samir", "100",
        "Samir mənə 100 manat verdi.", Instant.parse("2026-10-31T12:00:00Z"))

    @Test fun personSourceAndReceiptSurviveReopenAtomicallyWithoutPlaintextOrDuplicates() {
        val path = File(folder.root, "memory.bin")
        val store = VoiceMoneyStore(path) { key }
        assertEquals(VoiceStoreWrite.Saved, store.save(command()))
        assertFalse(path.readBytes().toString(Charsets.ISO_8859_1).contains("Samir"))
        val reopened = VoiceMoneyStore(path) { key }
        assertEquals(VoiceStoreWrite.Duplicate, reopened.save(command()))
        val snapshot = (reopened.load() as VoiceStoreRead.Ready).snapshot
        assertEquals(1, snapshot.persons.size); assertEquals(1, snapshot.receipts.size)
        assertEquals(10_000L, (VoiceMoneyRules.received(snapshot, "Samir") as VoiceMoneyAnswer.Found).totalMinor)
    }

    @Test fun lossOrCorruptionNeverLooksLikeEmptyMemoryOrSuccess() {
        val path = File(folder.root, "memory.bin")
        assertEquals(VoiceStoreWrite.Saved, VoiceMoneyStore(path) { key }.save(command()))
        val original = path.readBytes()
        val noKey = VoiceMoneyStore(path) { null }
        assertEquals(VoiceStoreRead.Unavailable, noKey.load())
        assertEquals(VoiceStoreWrite.Unavailable, noKey.save(command()))
        assertArrayEquals(original, path.readBytes())
        val damaged = original.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        path.writeBytes(damaged)
        assertEquals(VoiceStoreRead.Unavailable, VoiceMoneyStore(path) { key }.load())
        assertArrayEquals(damaged, path.readBytes())
    }

    @Test fun deletionOnlyRemovesTheRequestedReceiptAndSurvivesReopen() {
        val path = File(folder.root, "deletion.bin")
        val store = VoiceMoneyStore(path) { key }
        assertEquals(VoiceStoreWrite.Saved, store.save(command()))
        assertFalse(store.deleteReceipt("different"))
        assertTrue(store.deleteReceipt("receipt"))
        assertTrue((VoiceMoneyStore(path) { key }.load() as VoiceStoreRead.Ready).snapshot.receipts.isEmpty())
    }

    @Test fun explicitExportIncludesEvidenceAndFailsClosedWithoutKey() {
        val path = File(folder.root, "export.bin")
        val store = VoiceMoneyStore(path) { key }
        assertEquals(VoiceStoreWrite.Saved, store.save(command()))
        val export = org.json.JSONObject(store.exportJson()!!)
        assertEquals("memora-voice-memory-v1", export.getString("format"))
        assertEquals(command().source, export.getJSONArray("receipts").getJSONObject(0).getString("source"))
        assertNull(VoiceMoneyStore(path) { null }.exportJson())
    }
}

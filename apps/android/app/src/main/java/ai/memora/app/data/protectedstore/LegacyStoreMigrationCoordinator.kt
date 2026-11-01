package ai.memora.app.data.protectedstore

import ai.memora.app.data.CaptureRead
import ai.memora.app.data.EncryptedCaptureStore
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.VoiceMoneyStore
import ai.memora.app.data.VoiceStoreRead
import java.io.File

/** Reads only successfully decrypted/validated legacy snapshots; source files are never modified. */
internal class LegacyStoreMigrationCoordinator(
    private val captureStore: EncryptedCaptureStore,
    private val taskStore: EncryptedTaskStore,
    private val voiceMoneyStore: VoiceMoneyStore,
    private val encryptedFiles: Map<String, File>,
    private val destination: ProtectedMemoryRepository,
) {
    suspend fun migrate(): MigrationResult {
        require(encryptedFiles.keys == setOf("captures", "tasks", "voice-money"))
        val capture = (captureStore.load() as? CaptureRead.Ready)?.snapshot
            ?: error("Capture source cannot be decrypted and validated")
        val tasks = taskStore.migrationSnapshot()
        val voice = voiceMoneyStore.migrationSnapshot()
        val digests = encryptedFiles.mapValues { (_, file) ->
            if (!file.exists()) sha256(ByteArray(0)) else {
                val bytes = file.readBytes()
                try { sha256(bytes) } finally { bytes.fill(0) }
            }
        }
        val bundle = LegacyStoreBundle(
            capture = capture,
            tasks = tasks,
            money = voice.snapshot,
            encryptedFileDigests = digests,
            sourceSchemas = mapOf("captures" to capture.sourceSchema, "tasks" to tasks.sourceSchema,
                "voice-money" to voice.sourceSchema),
        )
        return destination.migrateLegacy(bundle)
    }
}

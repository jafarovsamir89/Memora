package ai.memora.app.data.protectedstore

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/** Authenticates a portable backup into private temporary files without touching the active database. */
internal object PortableBackupImporter {
    fun prepare(
        context: Context,
        source: InputStream,
        containerBytes: Long,
        passphrase: CharArray,
    ): PortableBackupFileCandidate {
        val id = UUID.randomUUID().toString()
        val plaintextEnvelope = AtomicFile(File(context.noBackupFilesDir, "memora-portable-import-$id.payload"))
        val snapshot = File(context.noBackupFilesDir, "memora-portable-import-$id.db")
        var temporaryOutput: FileOutputStream? = null
        var payloadCommitted = false
        try {
            temporaryOutput = plaintextEnvelope.startWrite()
            PortableBackupCipher().decrypt(source, containerBytes, passphrase, temporaryOutput)
            temporaryOutput.fd.sync()
            plaintextEnvelope.finishWrite(temporaryOutput)
            temporaryOutput = null
            payloadCommitted = true
            return PortableBackupPayload.extractSnapshotFile(plaintextEnvelope.baseFile, snapshot)
        } catch (error: Exception) {
            temporaryOutput?.let { stream -> runCatching { plaintextEnvelope.failWrite(stream) } }
            temporaryOutput = null
            throw error
        } finally {
            // This payload contains the SQLCipher key in its authenticated manifest. It never leaves app-private storage.
            plaintextEnvelope.delete()
            if (!payloadCommitted) File(plaintextEnvelope.baseFile.path + ".bak").delete()
        }
    }
}

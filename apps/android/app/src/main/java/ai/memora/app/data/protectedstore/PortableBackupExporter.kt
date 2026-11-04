package ai.memora.app.data.protectedstore

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.pm.PackageInfoCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.SecureRandom
import java.util.UUID

/** Builds an encrypted portable copy without materializing the database or envelope in memory. */
internal object PortableBackupExporter {
    fun export(
        context: Context,
        source: SupportSQLiteDatabase,
        passphrase: CharArray,
        destination: OutputStream,
    ): PortableBackupExportResult {
        val id = UUID.randomUUID().toString()
        val snapshot = File(context.noBackupFilesDir, "memora-portable-export-$id.db")
        val encryptedEnvelope = AtomicFile(File(context.noBackupFilesDir, "memora-portable-export-$id.tmp"))
        val snapshotKey = ByteArray(32).also(SecureRandom()::nextBytes)
        var prefix: ByteArray? = null
        var temporaryOutput: FileOutputStream? = null
        var envelopeCommitted = false
        try {
            PortableSqlCipherSnapshot.export(source, context, snapshot, snapshotKey)
            val appVersionCode = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            ).toInt()
            val prepared = PortableBackupPayload.headerForSnapshotFile(
                roomSchemaVersion = PortableBackupPayload.SUPPORTED_ROOM_SCHEMA,
                appVersionCode = appVersionCode,
                createdAtEpochMillis = System.currentTimeMillis(),
                snapshotKeyMaterial = snapshotKey,
                encryptedSnapshotFile = snapshot,
            )
            prefix = prepared.prefix
            temporaryOutput = encryptedEnvelope.startWrite()
            val encryptedBytes = SequenceInputStream(
                ByteArrayInputStream(prepared.prefix),
                FileInputStream(snapshot),
            ).use { payload ->
                PortableBackupCipher().encrypt(payload, prepared.payloadBytes, passphrase, temporaryOutput!!)
            }
            temporaryOutput!!.fd.sync()
            encryptedEnvelope.finishWrite(temporaryOutput)
            temporaryOutput = null
            envelopeCommitted = true
            FileInputStream(encryptedEnvelope.baseFile).use { input -> input.copyTo(destination, STREAM_BUFFER_BYTES) }
            destination.flush()
            return PortableBackupExportResult(prepared.manifest, encryptedBytes)
        } catch (error: Exception) {
            temporaryOutput?.let { stream -> runCatching { encryptedEnvelope.failWrite(stream) } }
            temporaryOutput = null
            throw error
        } finally {
            prefix?.fill(0)
            snapshotKey.fill(0)
            snapshot.delete()
            encryptedEnvelope.delete()
            if (!envelopeCommitted) File(encryptedEnvelope.baseFile.path + ".bak").delete()
        }
    }

    private const val STREAM_BUFFER_BYTES = 64 * 1024
}

internal data class PortableBackupExportResult(
    val manifest: PortableBackupManifest,
    val containerBytes: Long,
)

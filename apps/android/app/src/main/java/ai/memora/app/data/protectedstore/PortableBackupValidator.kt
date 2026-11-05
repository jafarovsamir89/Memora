package ai.memora.app.data.protectedstore

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Opens an imported encrypted snapshot only under a disposable app-private staging name. */
internal object PortableBackupValidator {
    fun validate(context: Context, backup: DecodedPortableBackup): PortableBackupValidationResult {
        val candidateSnapshot = File(context.noBackupFilesDir, "memora-portable-validation-source-${UUID.randomUUID()}.db")
        try {
            candidateSnapshot.writeBytes(backup.encryptedSnapshot)
        } catch (error: Exception) {
            candidateSnapshot.delete()
            throw error
        }
        val candidate = PortableBackupFileCandidate(
            backup.manifest,
            backup.snapshotKeyMaterial.copyOf(),
            candidateSnapshot,
        )
        return try {
            validate(context, candidate)
        } finally {
            candidate.close()
        }
    }

    fun validate(context: Context, backup: PortableBackupFileCandidate): PortableBackupValidationResult {
        require(PortableBackupPayload.isImportableRoomSchema(backup.manifest.roomSchemaVersion)) {
            "Unsupported Room schema version"
        }
        require(backup.encryptedSnapshotFile.isFile &&
            backup.manifest.encryptedSnapshotBytes.toLong() == backup.encryptedSnapshotFile.length()) {
            "Snapshot length does not match its manifest"
        }
        val databaseName = "memora-portable-validation-${UUID.randomUUID()}.db"
        val stagedFile = context.getDatabasePath(databaseName).canonicalFile
        check(!stagedFile.exists()) { "Staging database unexpectedly already exists" }
        check(stagedFile.parentFile == context.getDatabasePath("probe.db").canonicalFile.parentFile) {
            "Staging database must remain in the app-private database directory"
        }
        stagedFile.parentFile?.mkdirs()
        var staged: AgentDatabase? = null
        try {
            FileInputStream(backup.encryptedSnapshotFile).use { input ->
                FileOutputStream(stagedFile).use { output -> input.copyTo(output, COPY_BUFFER_BYTES); output.fd.sync() }
            }
            val firstPassphrase = PortableSqlCipherSnapshot.roomPassphraseBytes(backup.snapshotKeyMaterial)
            try {
                staged = AgentDatabase.openPortableSnapshotForValidation(context, databaseName, firstPassphrase)
            } finally {
                firstPassphrase.fill(0)
            }
            val firstReadback = inspect(staged.openHelper.writableDatabase)
            check(firstReadback.roomSchemaVersion == AGENT_DB_SCHEMA_VERSION) {
                "Opened snapshot did not migrate to the current Room schema"
            }
            staged.close()
            staged = null

            val reopenPassphrase = PortableSqlCipherSnapshot.roomPassphraseBytes(backup.snapshotKeyMaterial)
            try {
                staged = AgentDatabase.openPortableSnapshotForValidation(context, databaseName, reopenPassphrase)
            } finally {
                reopenPassphrase.fill(0)
            }
            val reopened = inspect(staged.openHelper.writableDatabase)
            check(reopened == firstReadback) { "Staged snapshot changed after close and reopen" }
            return reopened
        } finally {
            runCatching { staged?.close() }
            stagedFile.delete()
            File(stagedFile.path + "-journal").delete()
            File(stagedFile.path + "-wal").delete()
            File(stagedFile.path + "-shm").delete()
        }
    }

    internal fun inspectOpenedDatabase(database: SupportSQLiteDatabase): PortableBackupValidationResult = inspect(database)

    private fun inspect(database: SupportSQLiteDatabase): PortableBackupValidationResult {
        val version = database.query("PRAGMA user_version").use {
            check(it.moveToFirst()); it.getInt(0)
        }
        database.query("PRAGMA integrity_check").use {
            check(it.moveToFirst() && it.getString(0) == "ok") { "Imported snapshot failed integrity check" }
        }
        val rowCounts = mutableMapOf<String, Long>()
        database.query(
            "SELECT name FROM main.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val safeName = name.replace("\"", "\"\"")
                val rows = database.query("SELECT COUNT(*) FROM main.\"$safeName\"").use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                rowCounts[name] = rows
            }
        }
        check(rowCounts.isNotEmpty()) { "Imported snapshot has no application tables" }
        val profileFacts = database.query("SELECT COUNT(*) FROM profile_fact").use {
            check(it.moveToFirst()); it.getLong(0)
        }
        return PortableBackupValidationResult(version, rowCounts, profileFacts)
    }

    private const val COPY_BUFFER_BYTES = 64 * 1024
}

internal data class PortableBackupValidationResult(
    val roomSchemaVersion: Int,
    val tableRowCounts: Map<String, Long>,
    val profileFactCount: Long,
) {
    val tableCount: Int get() = tableRowCounts.size
}

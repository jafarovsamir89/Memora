package ai.memora.app.data.protectedstore

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Same-directory SQLCipher file cutover with a durable journal and the prior wrapped key retained for rollback. */
internal object PortableBackupCutover {
    enum class RecoveryOutcome { NONE, CANCELLED_BEFORE_SWAP, ROLLED_BACK, ACTIVATED }

    fun arm(
        context: Context,
        databaseName: String,
        provider: DatabaseKeyProvider,
        candidate: PortableBackupFileCandidate,
        explicitUserConfirmation: Boolean,
        closeActiveDatabase: () -> Unit,
        afterDurableStep: (String) -> Unit = {},
    ) {
        require(explicitUserConfirmation) { "Portable restore requires explicit confirmation" }
        require(databaseName.matches(DATABASE_NAME_PATTERN)) { "Unsupported active database name" }
        require(provider.databaseFile.canonicalFile == context.getDatabasePath(databaseName).canonicalFile) {
            "Key provider does not target the requested database"
        }
        require(candidate.manifest.roomSchemaVersion == PortableBackupPayload.SUPPORTED_ROOM_SCHEMA)
        require(candidate.encryptedSnapshotFile.isFile &&
            candidate.encryptedSnapshotFile.length() == candidate.manifest.encryptedSnapshotBytes.toLong())

        val active = provider.databaseFile.canonicalFile
        val candidateFile = sibling(active, CANDIDATE_SUFFIX)
        val rollbackFile = sibling(active, ROLLBACK_SUFFIX)
        val rejectedFile = sibling(active, REJECTED_SUFFIX)
        val journal = journalFile(context)
        check(!hasJournal(journal) && !provider.hasPortableRestoreEnvelopes()) {
            "A previous portable restore requires startup recovery"
        }
        check(active.isFile) { "Cannot restore without an existing encrypted database" }
        check(!candidateFile.exists() && !rollbackFile.exists() && !rejectedFile.exists()) {
            "Portable restore artifacts already exist"
        }

        val freeBytes = StatFs(active.parentFile!!.path).availableBytes
        check(freeBytes >= candidate.encryptedSnapshotFile.length() + MIN_FREE_BYTES_AFTER_COPY) {
            "Insufficient free space for portable restore"
        }

        var journalDurable = false
        try {
            copyAndSync(candidate.encryptedSnapshotFile, candidateFile)
            provider.preserveEnvelopeForPortableRollback()
            provider.stagePortableRestoreKey(candidate.snapshotKeyMaterial)
            closeActiveDatabase()
            refuseUnexpectedSidecars(active)
            writeJournal(journal, Phase.PREPARED)
            journalDurable = true
            afterDurableStep("PREPARED")
            atomicRename(active, rollbackFile)
            afterDurableStep("OLD_DB_RENAMED")
            writeJournal(journal, Phase.OLD_DB_MOVED)
            afterDurableStep("OLD_DB_MOVED")
            atomicRename(candidateFile, active)
            afterDurableStep("CANDIDATE_RENAMED")
            writeJournal(journal, Phase.CANDIDATE_INSTALLED)
            afterDurableStep("CANDIDATE_INSTALLED")
        } catch (error: Exception) {
            if (!journalDurable) {
                candidateFile.delete()
                provider.clearPortableRestoreEnvelopes()
            }
            throw PortableBackupCutoverException(
                if (journalDurable) "Restore is pending startup recovery" else "Could not prepare portable restore",
                error,
            )
        }
    }

    /** Must run before opening the active Room singleton. The callback opens, integrity-checks, closes and reopens. */
    fun recoverBeforeDatabaseOpen(
        context: Context,
        databaseName: String,
        provider: DatabaseKeyProvider,
        afterRollbackStep: (String) -> Unit = {},
        validateAndReopen: (databaseName: String, key: ByteArray) -> Unit,
    ): RecoveryOutcome {
        require(databaseName.matches(DATABASE_NAME_PATTERN)) { "Unsupported active database name" }
        val journal = journalFile(context)
        val phase = readJournal(journal)
        if (phase == null) {
            check(!provider.hasPortableRestoreEnvelopes() && !restoreArtifacts(context, databaseName).isNotEmpty()) {
                "Portable restore artifacts exist without a journal"
            }
            return RecoveryOutcome.NONE
        }

        val active = context.getDatabasePath(databaseName).canonicalFile
        val rollback = sibling(active, ROLLBACK_SUFFIX)
        val candidate = sibling(active, CANDIDATE_SUFFIX)
        val rejected = sibling(active, REJECTED_SUFFIX)

        if (phase == Phase.PREPARED && active.exists() && !rollback.exists()) {
            deleteIfExists(candidate)
            provider.clearPortableRestoreEnvelopes()
            deleteJournal(journal)
            syncDirectory(active.parentFile!!)
            syncDirectory(journal.baseFile.parentFile!!)
            return RecoveryOutcome.CANCELLED_BEFORE_SWAP
        }

        if (!active.exists() && rollback.exists()) {
            return rollbackToPrevious(context, databaseName, provider, active, rollback, candidate, rejected, journal,
                validateAndReopen, afterRollbackStep = afterRollbackStep)
        }

        // A rollback may be interrupted after the old DB has returned to its stable path.
        if (active.exists() && !rollback.exists() && rejected.exists() && phase != Phase.VERIFIED) {
            val oldKey = provider.loadOrCreate()
            try {
                validateAndReopen(databaseName, oldKey)
            } catch (error: Exception) {
                oldKey.fill(0)
                throw PortableBackupCutoverException(
                    "Previous database is back at the active path but could not be verified; recovery artifacts were preserved",
                    error,
                )
            }
            oldKey.fill(0)
            deleteIfExists(candidate)
            deleteIfExists(rejected)
            syncDirectory(active.parentFile!!)
            provider.clearPortableRestoreEnvelopes()
            deleteJournal(journal)
            syncDirectory(journal.baseFile.parentFile!!)
            return RecoveryOutcome.ROLLED_BACK
        }

        if (active.exists() && rollback.exists()) {
            val candidateKey = try {
                provider.loadPortableRestoreKey()
                    ?: throw DatabaseKeyUnavailableException("Staged portable restore key is unavailable")
            } catch (error: Exception) {
                return rollbackToPrevious(context, databaseName, provider, active, rollback, candidate, rejected,
                    journal, validateAndReopen, error, afterRollbackStep)
            }
            try {
                validateAndReopen(databaseName, candidateKey)
                provider.promotePortableRestoreKey()
                writeJournal(journal, Phase.NEW_KEY_PROMOTED)
            } catch (error: Exception) {
                candidateKey.fill(0)
                return rollbackToPrevious(context, databaseName, provider, active, rollback, candidate, rejected,
                    journal, validateAndReopen, error, afterRollbackStep)
            }
            candidateKey.fill(0)
            return verifyAndFinalize(context, databaseName, provider, active, rollback, candidate, rejected,
                journal, validateAndReopen, afterRollbackStep)
        }

        if (active.exists() && phase == Phase.VERIFIED) {
            return verifyAndFinalize(context, databaseName, provider, active, rollback, candidate, rejected,
                journal, validateAndReopen, afterRollbackStep)
        }

        throw PortableBackupCutoverException("Portable restore journal and encrypted database files are inconsistent")
    }

    private fun verifyAndFinalize(
        context: Context,
        databaseName: String,
        provider: DatabaseKeyProvider,
        active: File,
        rollback: File,
        candidate: File,
        rejected: File,
        journal: AtomicFile,
        validateAndReopen: (String, ByteArray) -> Unit,
        afterRollbackStep: (String) -> Unit,
    ): RecoveryOutcome {
        val activeKey = try {
            provider.loadOrCreate()
        } catch (error: Exception) {
            return rollbackToPrevious(context, databaseName, provider, active, rollback, candidate, rejected,
                journal, validateAndReopen, error, afterRollbackStep)
        }
        try {
            val stagedKey = provider.loadPortableRestoreKey()
            if (stagedKey != null) {
                val sameKey = MessageDigest.isEqual(activeKey, stagedKey)
                stagedKey.fill(0)
                check(sameKey) { "Promoted restore key does not match staged key" }
            }
            validateAndReopen(databaseName, activeKey)
            writeJournal(journal, Phase.VERIFIED)
        } catch (error: Exception) {
            activeKey.fill(0)
            return rollbackToPrevious(context, databaseName, provider, active, rollback, candidate, rejected,
                journal, validateAndReopen, error, afterRollbackStep)
        }
        activeKey.fill(0)

        // Keep journal+new active key until old artifacts are removed; VERIFIED recovery is idempotent.
        deleteIfExists(rollback)
        deleteIfExists(rejected)
        deleteIfExists(candidate)
        syncDirectory(active.parentFile!!)
        provider.clearPortableRestoreEnvelopes()
        deleteJournal(journal)
        syncDirectory(journal.baseFile.parentFile!!)
        return RecoveryOutcome.ACTIVATED
    }

    private fun rollbackToPrevious(
        context: Context,
        databaseName: String,
        provider: DatabaseKeyProvider,
        active: File,
        rollback: File,
        candidate: File,
        rejected: File,
        journal: AtomicFile,
        validateAndReopen: (String, ByteArray) -> Unit,
        cause: Exception? = null,
        afterRollbackStep: (String) -> Unit = {},
    ): RecoveryOutcome {
        if (!rollback.isFile) {
            throw PortableBackupCutoverException("Previous encrypted database is unavailable; recovery artifacts preserved", cause)
        }
        try {
            provider.rollbackPortableRestoreEnvelope()
            if (active.exists()) {
                if (rejected.exists()) throw IOException("Rejected-candidate path is unexpectedly occupied")
                atomicRename(active, rejected)
            }
            atomicRename(rollback, active)
            afterRollbackStep("OLD_DB_RESTORED")
            val oldKey = provider.loadOrCreate()
            try {
                validateAndReopen(databaseName, oldKey)
            } finally {
                oldKey.fill(0)
            }
            deleteIfExists(candidate)
            deleteIfExists(rejected)
            syncDirectory(active.parentFile!!)
            provider.clearPortableRestoreEnvelopes()
            deleteJournal(journal)
            syncDirectory(journal.baseFile.parentFile!!)
            return RecoveryOutcome.ROLLED_BACK
        } catch (rollbackError: Exception) {
            throw PortableBackupCutoverException(
                "Restore failed and previous database could not be fully reopened; encrypted recovery artifacts were preserved",
                cause ?: rollbackError,
            )
        }
    }

    private fun refuseUnexpectedSidecars(active: File) {
        val sidecars = listOf("-journal", "-wal", "-shm").map { File(active.path + it) }
        check(sidecars.none { it.exists() }) { "Database sidecars remain after close; refusing portable restore" }
    }

    private fun copyAndSync(source: File, destination: File) {
        check(!destination.exists())
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output, COPY_BUFFER_BYTES); output.fd.sync() }
        }
        check(destination.length() == source.length()) { "Portable restore candidate copy was incomplete" }
        syncDirectory(destination.parentFile!!)
    }

    private fun atomicRename(source: File, destination: File) {
        check(source.exists() && !destination.exists()) { "Portable restore rename precondition failed" }
        Os.rename(source.path, destination.path)
        syncDirectory(destination.parentFile!!)
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun deleteIfExists(file: File) {
        check(!file.exists() || file.delete()) { "Could not remove portable restore artifact" }
        check(!file.exists()) { "Portable restore artifact remains after cleanup" }
    }

    private fun deleteJournal(journal: AtomicFile) {
        journal.delete()
        check(!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) {
            "Portable restore journal remains after cleanup"
        }
    }

    private fun writeJournal(journal: AtomicFile, phase: Phase) {
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(JOURNAL_MAGIC)
                output.writeByte(JOURNAL_VERSION)
                output.writeByte(phase.ordinal)
            }
            buffer.toByteArray()
        }
        var file: FileOutputStream? = null
        try {
            file = journal.startWrite()
            file.write(bytes)
            file.fd.sync()
            journal.finishWrite(file)
            file = null
            syncDirectory(journal.baseFile.parentFile!!)
        } catch (error: Exception) {
            file?.let { stream -> runCatching { journal.failWrite(stream) } }
            throw IOException("Could not persist portable restore journal", error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readJournal(journal: AtomicFile): Phase? {
        val base = journal.baseFile
        if (!base.exists() && !File(base.path + ".bak").exists()) return null
        val bytes = try { journal.openRead().use { it.readBytes() } }
        catch (error: Exception) { throw PortableBackupCutoverException("Could not read portable restore journal", error) }
        return try {
            require(bytes.size == JOURNAL_BYTES) { "Invalid portable restore journal length" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == JOURNAL_MAGIC && input.readUnsignedByte() == JOURNAL_VERSION) {
                    "Unsupported portable restore journal"
                }
                Phase.entries.getOrNull(input.readUnsignedByte())
                    ?: throw IllegalArgumentException("Unknown portable restore phase")
            }
        } catch (error: Exception) {
            throw PortableBackupCutoverException("Portable restore journal is invalid; recovery files were preserved", error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun journalFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, JOURNAL_NAME))

    private fun hasJournal(journal: AtomicFile) = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()

    private fun restoreArtifacts(context: Context, databaseName: String): List<File> {
        val active = context.getDatabasePath(databaseName)
        return listOf(sibling(active, CANDIDATE_SUFFIX), sibling(active, ROLLBACK_SUFFIX), sibling(active, REJECTED_SUFFIX))
            .filter { it.exists() }
    }

    private fun sibling(file: File, suffix: String) = File(file.parentFile, file.name + suffix)

    private enum class Phase { PREPARED, OLD_DB_MOVED, CANDIDATE_INSTALLED, NEW_KEY_PROMOTED, VERIFIED }

    private const val JOURNAL_MAGIC = 0x4D525354 // MRST
    private const val JOURNAL_VERSION = 1
    private const val JOURNAL_BYTES = 6
    private const val JOURNAL_NAME = "memora-portable-restore.journal"
    private const val CANDIDATE_SUFFIX = ".portable-restore-candidate"
    private const val ROLLBACK_SUFFIX = ".portable-restore-rollback"
    private const val REJECTED_SUFFIX = ".portable-restore-rejected"
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val MIN_FREE_BYTES_AFTER_COPY = 16L * 1024 * 1024
    private val DATABASE_NAME_PATTERN = Regex("memora-[A-Za-z0-9._-]+\\.db")
}

internal class PortableBackupCutoverException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

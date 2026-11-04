package ai.memora.app.data.protectedstore

import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import android.util.Log
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ai.memora.app.data.EncryptedCaptureStore
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.VoiceMoneyStore
import ai.memora.app.agent.*
import ai.memora.app.data.CaptureRead
import ai.memora.app.data.StoreRead
import ai.memora.app.data.VoiceStoreRead
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.UUID

/** Manual, local-only probe. Requires explicit opt-in and writes a synthetic test-named database. */
object DatabaseStorageProbe {
    private class StartWriteFailingAtomicFile(baseFile: File) : AtomicFile(baseFile) {
        override fun startWrite(): FileOutputStream = throw IOException("synthetic ENOSPC")
    }

    /** Checks Android's own JCA implementation with synthetic bytes; no app database is read or written. */
    fun runPortableBackupCipherProbe(permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted) {
            result.putString("status", "explicit_synthetic_opt_in_required")
            return result
        }
        return try {
            val cipher = PortableBackupCipher()
            val payload = "synthetic protected backup payload".toByteArray()
            val passphrase = "synthetic backup passphrase".toCharArray()
            val container = cipher.encrypt(payload, passphrase)
            val roundTrip = cipher.decrypt(container, passphrase).contentEquals(payload)
            val wrongPasswordRejected = runCatching {
                cipher.decrypt(container, "wrong synthetic passphrase".toCharArray())
            }.exceptionOrNull() is PortableBackupAuthenticationException
            check(roundTrip && wrongPasswordRejected)
            result.putBoolean("android_jca_round_trip_passed", roundTrip)
            result.putBoolean("wrong_passphrase_refused", wrongPasswordRejected)
            result.putString("status", "passed")
            result
        } catch (error: Exception) {
            result.putString("status", "failed")
            result.putString("error_type", error.javaClass.simpleName)
            result
        }
    }

    /** Injects no-space-at-startWrite failures before rekey and during staged-key promotion. */
    fun runEnvelopeWriteFailureProbe(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }
        val name = "memora-agent-envelope-write-failure-probe.db"
        val alias = "ai.memora.app.test.envelope-write-failure-probe.v1"
        val databaseFile = context.getDatabasePath(name)
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val stagedEnvelope = AtomicFile(File(envelope.baseFile.parentFile, "${envelope.baseFile.name}.rotation"))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: AgentDatabase? = null
        var oldKey: ByteArray? = null
        var nextKey: ByteArray? = null
        var stage = "open_initial_database"
        try {
            System.loadLibrary("sqlcipher")
            val provider = DatabaseKeyProvider(context, databaseFile, envelope, keyStore, alias, stagedEnvelope)
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            stage = "write_synthetic_marker"
            oldKey = provider.loadOrCreate()
            database!!.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-envelope-write-failure-marker", "c".repeat(64), "COMMITTED", "[]"),
            )
            nextKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)

            stage = "inject_stage_write_failure"
            val stagedFailureProvider = DatabaseKeyProvider(
                context, databaseFile, envelope, keyStore, alias,
                StartWriteFailingAtomicFile(stagedEnvelope.baseFile),
            )
            val stageWriteFailedClosed = runCatching { stagedFailureProvider.stageRotation(nextKey!!) }
                .exceptionOrNull() is DatabaseKeyUnavailableException
            val oldKeyStillReadsDatabase = provider.loadOrCreate().contentEquals(oldKey) &&
                database!!.openHelper.readableDatabase.query(
                    "SELECT commandId FROM agent_command_journal WHERE commandId = 'synthetic-envelope-write-failure-marker'",
                ).use { it.moveToFirst() }
            result.putBoolean("stage_envelope_write_failure_refused_before_rekey", stageWriteFailedClosed)
            result.putBoolean("old_key_and_synthetic_database_survived_stage_failure", oldKeyStillReadsDatabase)
            result.putBoolean("failed_stage_envelope_was_not_created", !stagedEnvelope.baseFile.exists())
            check(stageWriteFailedClosed && oldKeyStillReadsDatabase && !stagedEnvelope.baseFile.exists())

            stage = "perform_sqlcipher_rekey"
            provider.stageRotation(nextKey!!)
            (database!!.openHelper.writableDatabase as net.zetetic.database.sqlcipher.SQLiteDatabase)
                .changePassword(nextKey!!)
            database!!.close()
            database = null
            oldKey!!.fill(0)
            oldKey = null

            stage = "inject_promotion_write_failure"
            val failedPromotionProvider = DatabaseKeyProvider(
                context, databaseFile, StartWriteFailingAtomicFile(envelope.baseFile), keyStore, alias, stagedEnvelope,
            )
            val promotionFailure = runCatching {
                AgentDatabase.openForSyntheticProbe(context, failedPromotionProvider, name)
            }.exceptionOrNull()
            val stagedKeySurvivedPromotionFailure = promotionFailure is DatabaseKeyUnavailableException &&
                stagedEnvelope.baseFile.exists()
            result.putBoolean("promotion_write_failure_preserved_staged_recovery_key", stagedKeySurvivedPromotionFailure)

            stage = "startup_recovery_after_promotion_failure"
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            val markerSurvived = database!!.openHelper.readableDatabase.query(
                "SELECT commandId FROM agent_command_journal WHERE commandId = 'synthetic-envelope-write-failure-marker'",
            ).use { it.moveToFirst() }
            val recoveredKey = provider.loadOrCreate()
            val recoveryCompleted = recoveredKey.contentEquals(nextKey) && provider.loadStagedRotation() == null
            recoveredKey.fill(0)
            result.putBoolean("startup_recovered_after_promotion_write_failure", recoveryCompleted)
            result.putBoolean("synthetic_marker_survived", markerSurvived)
            check(stageWriteFailedClosed && oldKeyStillReadsDatabase && stagedKeySurvivedPromotionFailure &&
                markerSurvived && recoveryCompleted)
            result.putString("status", "passed")
            result.putBoolean("stage_envelope_write_failure_refused_before_rekey", true)
            result.putBoolean("old_key_and_synthetic_database_survived_stage_failure", true)
            result.putBoolean("promotion_write_failure_preserved_staged_recovery_key", true)
            result.putBoolean("startup_recovered_after_promotion_write_failure", true)
            result.putBoolean("synthetic_marker_survived", true)
        } catch (error: Exception) {
            result.putString("status", "failed")
            result.putString("error_type", error.javaClass.simpleName)
            result.putString("failure_stage", stage)
            result.putString("failure_reason", "synthetic_atomic_file_write_failure_recovery_not_verified")
        } finally {
            runCatching { database?.close() }
            oldKey?.fill(0)
            nextKey?.fill(0)
            context.deleteDatabase(name)
            listOf(envelope.baseFile, File(envelope.baseFile.path + ".bak"), stagedEnvelope.baseFile,
                File(stagedEnvelope.baseFile.path + ".bak")) .forEach { it.delete() }
            keyStore.deleteEntry(alias)
        }
        return result
    }

    fun run(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted ||
            context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0
        ) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }

        val databaseName = "memora-agent-storage-probe.db"
        val envelopeName = "memora-agent-storage-probe-key.v1"
        val wrappingAlias = "ai.memora.app.test.storage-probe.v1"
        val databaseFile = context.getDatabasePath(databaseName)
        val envelope = AtomicFile(File(context.noBackupFilesDir, envelopeName))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: ProtectedProbeDatabase? = null
        var wrongKeyDatabase: ProtectedProbeDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            val provider = DatabaseKeyProvider(context, databaseFile, envelope, keyStore, wrappingAlias)
            val firstKey = provider.loadOrCreate()
            database = open(context, databaseName, firstKey)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-probe-command", "a".repeat(64), "COMMITTED", "[]"),
            )
            check(readCommandId(database) == "synthetic-probe-command")
            val journalMode = database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val walFile = File(databaseFile.parentFile, databaseFile.name + "-wal")
            val shmFile = File(databaseFile.parentFile, databaseFile.name + "-shm")
            val walExists = walFile.isFile && walFile.length() > 0
            val shmExists = shmFile.isFile && shmFile.length() > 0
            val walHasPlaintext = walExists && walFile.readBytes().useBytes { it.containsAscii("synthetic-probe-command") }
            val shmHasPlaintext = shmExists && shmFile.readBytes().useBytes { it.containsAscii("synthetic-probe-command") }
            result.putString("journal_mode_observed", journalMode)
            result.putBoolean("wal_file_present", walExists)
            result.putBoolean("shm_file_present", shmExists)
            result.putBoolean("wal_plaintext_marker_found", walHasPlaintext)
            result.putBoolean("shm_plaintext_marker_found", shmHasPlaintext)
            check(!journalMode.equals("wal", ignoreCase = true) && !walHasPlaintext && !shmHasPlaintext)
            database.close()
            database = null
            firstKey.fill(0)

            val secondKey = provider.loadOrCreate()
            check(secondKey.size == 32)
            val wrongKey = ByteArray(32) { (it + 1).toByte() }
            wrongKeyDatabase = open(context, databaseName, wrongKey)
            val wrongKeyRejected = runCatching { wrongKeyDatabase!!.openHelper.writableDatabase }.isFailure
            wrongKeyDatabase!!.close()
            wrongKeyDatabase = null
            wrongKey.fill(0)
            check(wrongKeyRejected)

            val keyLossRefused = verifyMissingWrappingKeyFailsClosed(context)
            val missingEnvelopeRefused = verifyMissingEnvelopeFailsClosed(context)
            val corruptEnvelopeRefused = verifyCorruptEnvelopeFailsClosed(context)
            val corruptionFailsClosed = verifyCorruptDatabaseFailsClosed(context)
            val keyRotationRecovered = verifySyntheticKeyRotationRecovery(context)

            database = open(context, databaseName, secondKey)
            check(readCommandId(database) == "synthetic-probe-command")
            database.close()
            database = null
            val encryptedBytes = databaseFile.readBytes()
            check(!encryptedBytes.containsAscii("synthetic-probe-command"))
            check(!encryptedBytes.contentStartsWith("SQLite format 3\u0000".toByteArray()))
            encryptedBytes.fill(0)
            secondKey.fill(0)
            result.putString("status", "passed")
            result.putBoolean("reopen_with_correct_key", true)
            result.putBoolean("wrong_key_rejected_without_data_loss", true)
            result.putBoolean("missing_wrapping_key_refused_without_data_loss", keyLossRefused)
            result.putBoolean("missing_envelope_refused_without_data_loss", missingEnvelopeRefused)
            result.putBoolean("corrupt_envelope_refused_without_database_loss", corruptEnvelopeRefused)
            result.putBoolean("database_corruption_refused_without_overwrite", corruptionFailsClosed)
            result.putBoolean("synthetic_key_rotation_recovered_and_verified", keyRotationRecovered)
            result.putBoolean("closed_database_has_no_plaintext_marker", true)
            result.putBoolean("wal_remains_disabled_and_auxiliary_files_have_no_plaintext_marker", true)
        } catch (error: Exception) {
            result.putString("status", "failed")
            result.putString("error_type", error.javaClass.simpleName)
            error.message?.filter { it.isLetter() || it == '_' || it == ' ' }?.take(100)?.let { result.putString("failure_reason", it) }
            if (error is SyntheticProbeFailure) {
                result.putString("failure_stage", error.stage)
                error.reason?.let { result.putString("failure_reason", it) }
                error.diagnostic?.let { result.putString("synthetic_import_row_ids", it) }
            }
        } finally {
            runCatching { database?.close() }
            runCatching { wrongKeyDatabase?.close() }
            context.deleteDatabase(databaseName)
            envelope.baseFile.delete()
            keyStore.deleteEntry(wrappingAlias)
        }
        return result
    }

    /** Host-driven synthetic process-death probe; leave DB open with an uncommitted transaction until force-stop. */
    fun prepareProcessDeathProbe(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }
        val name = "memora-agent-process-death-probe.db"
        val alias = "ai.memora.app.test.process-death-probe.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        var database: ProtectedProbeDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = DatabaseKeyProvider(context, context.getDatabasePath(name), envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            val sql = database.openHelper.writableDatabase
            sql.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-process-death-committed", "d".repeat(64), "COMMITTED", "[]"),
            )
            sql.beginTransaction()
            sql.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-process-death-uncommitted", "e".repeat(64), "PREPARED", "[]"),
            )
            result.putString("status", "prepared")
            result.putBoolean("committed_synthetic_row_written", true)
            result.putBoolean("uncommitted_synthetic_row_open", true)
            Log.i("MemoraStorageProbe", "P1_PROCESS_DEATH_PROBE_READY")
            while (true) Thread.sleep(1_000)
        } catch (error: Exception) {
            result.putString("status", "prepare_failed")
            result.putString("error_type", error.javaClass.simpleName)
            result.putString("failure_reason", "synthetic_process_death_fixture_not_prepared")
            runCatching { database?.close() }
        }
        return result
    }

    /** Reopen the process-death fixture, prove transaction rollback/durability, then remove only named test files. */
    fun verifyProcessDeathProbe(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }
        val name = "memora-agent-process-death-probe.db"
        val alias = "ai.memora.app.test.process-death-probe.v1"
        val databaseFile = context.getDatabasePath(name)
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: ProtectedProbeDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            val key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            val committedSurvived = hasCommand(database, "synthetic-process-death-committed")
            val uncommittedRolledBack = !hasCommand(database, "synthetic-process-death-uncommitted")
            database.close(); database = null; key.fill(0)
            val bytes = databaseFile.readBytes()
            val noPlaintextMarker = !bytes.containsAscii("synthetic-process-death-committed") &&
                !bytes.containsAscii("synthetic-process-death-uncommitted")
            bytes.fill(0)
            check(committedSurvived && uncommittedRolledBack && noPlaintextMarker)
            result.putString("status", "passed")
            result.putBoolean("committed_transaction_survived_process_death", true)
            result.putBoolean("uncommitted_transaction_rolled_back", true)
            result.putBoolean("reopened_database_has_no_plaintext_marker", true)
        } catch (error: Exception) {
            result.putString("status", "failed")
            result.putString("error_type", error.javaClass.simpleName)
            result.putString("failure_reason", "synthetic_process_death_recovery_not_verified")
        } finally {
            runCatching { database?.close() }
            context.deleteDatabase(name)
            envelope.baseFile.delete()
            store.deleteEntry(alias)
        }
        return result
    }

    /** Host force-stops the app after SQLCipher rekey but before the new key envelope is promoted. */
    fun prepareKeyRotationProcessDeathProbe(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }
        val name = "memora-agent-rotation-death-probe.db"
        val alias = "ai.memora.app.test.rotation-death-probe.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val databaseFile = context.getDatabasePath(name)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: AgentDatabase? = null
        var oldKey: ByteArray? = null
        var nextKey: ByteArray? = null
        try {
            System.loadLibrary("sqlcipher")
            val provider = DatabaseKeyProvider(context, databaseFile, envelope, store, alias)
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            oldKey = provider.loadOrCreate()
            database!!.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-rotation-force-stop-marker", "b".repeat(64), "COMMITTED", "[]"),
            )
            nextKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            provider.stageRotation(nextKey!!)
            val cipherDatabase = database!!.openHelper.writableDatabase as net.zetetic.database.sqlcipher.SQLiteDatabase
            cipherDatabase.changePassword(nextKey!!)
            oldKey!!.fill(0); oldKey = null
            nextKey!!.fill(0); nextKey = null
            result.putString("status", "prepared")
            result.putBoolean("synthetic_rekey_complete_envelope_promotion_pending", true)
            Log.i("MemoraStorageProbe", "P1_KEY_ROTATION_FORCE_STOP_READY")
            while (true) Thread.sleep(1_000)
        } catch (error: Exception) {
            result.putString("status", "prepare_failed")
            result.putString("error_type", error.javaClass.simpleName)
            result.putString("failure_reason", "synthetic_key_rotation_fixture_not_prepared")
            runCatching { database?.close() }
            oldKey?.fill(0)
            nextKey?.fill(0)
        }
        return result
    }

    /** Reopens through the production startup reconciliation after host force-stop and removes only this fixture. */
    fun verifyKeyRotationProcessDeathProbe(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }
        val name = "memora-agent-rotation-death-probe.db"
        val alias = "ai.memora.app.test.rotation-death-probe.v1"
        val databaseFile = context.getDatabasePath(name)
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: AgentDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            val provider = DatabaseKeyProvider(context, databaseFile, envelope, store, alias)
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            val markerSurvived = database!!.openHelper.readableDatabase.query(
                "SELECT commandId FROM agent_command_journal WHERE commandId = 'synthetic-rotation-force-stop-marker'",
            ).use { it.moveToFirst() }
            check(markerSurvived && provider.loadStagedRotation() == null)
            val currentKey = provider.loadOrCreate()
            check(currentKey.size == 32)
            currentKey.fill(0)
            result.putString("status", "passed")
            result.putBoolean("rekey_recovered_after_process_force_stop", true)
            result.putBoolean("synthetic_marker_survived", true)
            result.putBoolean("staged_envelope_promoted", true)
        } catch (error: Exception) {
            result.putString("status", "failed")
            result.putString("error_type", error.javaClass.simpleName)
            result.putString("failure_reason", "synthetic_key_rotation_process_death_recovery_not_verified")
        } finally {
            runCatching { database?.close() }
            context.deleteDatabase(name)
            listOf(envelope.baseFile, File(envelope.baseFile.path + ".bak"),
                File(envelope.baseFile.path + ".rotation"), File(envelope.baseFile.path + ".rotation.bak"))
                .forEach { it.delete() }
            store.deleteEntry(alias)
        }
        return result
    }

    /** Exercises Room transactions and idempotent migration on a separate synthetic SQLCipher DB. */
    fun runMigration(context: Context, permitted: Boolean): Bundle {
        val result = Bundle()
        if (!permitted || context.packageName != "ai.memora.app" ||
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            result.putString("status", "explicit_debug_synthetic_opt_in_required")
            return result
        }
        val dbName = "memora-agent-migration-probe.db"
        val alias = "ai.memora.app.test.migration-probe.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "memora-agent-migration-probe-key.v1"))
        val databaseFile = context.getDatabasePath(dbName)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var db: AgentDatabase? = null
        var sourceFixtureDirectory: File? = null
        try {
            System.loadLibrary("sqlcipher")
            verifyLegacyStoreReaders(context)
            verifyRoomSchemaUpgrade(context, store)
            val key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
                .addMigrations(AGENT_DB_1_TO_2, AGENT_DB_2_TO_3, AGENT_DB_3_TO_4, AGENT_DB_4_TO_5,
                    AGENT_DB_5_TO_6, AGENT_DB_6_TO_7, AGENT_DB_7_TO_8, AGENT_DB_8_TO_9, AGENT_DB_9_TO_10, AGENT_DB_10_TO_11,
                    AGENT_DB_11_TO_12, AGENT_DB_12_TO_13, AGENT_DB_13_TO_14, AGENT_DB_14_TO_15, AGENT_DB_15_TO_16, AGENT_DB_16_TO_17,
                    AGENT_DB_17_TO_18)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .openHelperFactory(SupportOpenHelperFactory(key, null, false))
                .build()
            db.openHelper.writableDatabase

            val repository = ProtectedMemoryRepository(db)
            val (coordinator, fixtureDirectory) = createMigrationCoordinator(context, repository)
            sourceFixtureDirectory = fixtureDirectory
            val first = runBlocking { coordinator.migrate() }
            check(first is MigrationResult.Applied)
            val retry = runBlocking { coordinator.migrate() }
            check(retry == MigrationResult.AlreadyApplied)
            runBlocking {
                check(runCatching {
                    repository.recordPendingCommand("synthetic-invalid-version", "0".repeat(64),
                        "[{\"entityId\":\"synthetic-task\",\"version\":2.0}]")
                }.isFailure)
                repository.recordPendingCommand("synthetic-pending-command", "d".repeat(64),
                    "[{\"entityId\":\"synthetic-task\",\"version\":2}]")
                repository.recordPendingCommand("synthetic-pending-command", "d".repeat(64),
                    "[{\"entityId\":\"synthetic-task\",\"version\":2}]")
                check(runCatching {
                    repository.recordPendingCommand("synthetic-pending-command", "e".repeat(64), "[]")
                }.isFailure)
                repository.enqueue("synthetic-operation", "synthetic-pending-command", "f".repeat(64),
                    "[{\"entityId\":\"synthetic-task\",\"version\":2}]")
                repository.updateOutbox("synthetic-operation", "retry")
                check(db!!.protectedStoreDao().outbox("synthetic-operation")?.status == "retry")
                repository.removeOutbox("synthetic-operation")
                repository.forgetResolvedCommand("synthetic-pending-command")
            }
            val runtimeJournal = RoomAgentRunJournal(db)
            val recoveryTurnId = "00000000-0000-4000-8000-000000000041"
            runBlocking {
                runtimeJournal.beginTurn(recoveryTurnId)
                runtimeJournal.setTurnStatus(recoveryTurnId, AgentTurnStatus.EXECUTING)
                val commandId = "00000000-0000-4000-8000-000000000042"
                runtimeJournal.recordCommand(AgentCommandJournalEntry(commandId, "9".repeat(64), "prepared",
                    mapOf("00000000-0000-4000-8000-000000000043" to 7L)))
                runtimeJournal.setCommandStatus(commandId, "prepared", "applying")
                runtimeJournal.setCommandStatus(commandId, "applying", "reconciling")
                check(runtimeJournal.command(commandId)?.targetVersions?.get("00000000-0000-4000-8000-000000000043") == 7L)
                check(runtimeJournal.unresolvedCommands().single().status == "reconciling")
            }
            val replayed = runBlocking { repository.tasks() }
            check(replayed.tasks.single().title == "Call dentist" && replayed.tasks.single().version == 2L)
            check(replayed.deletedIds == setOf("deleted-task-marker"))
            verifyP3P6Domain(db!!)
            verifyFinanceClarificationChain(db!!)
            val aliasProbe = verifyPersonAliasChain(db!!)
            seedCanonicalCaseContinuityProbe(db!!)
            runBlocking {
                val archive = ProtectedConversationRepository(db!!)
                val now = System.currentTimeMillis()
                archive.appendTurn("synthetic-retention", "synthetic-old-turn", "synthetic expired utterance", "synthetic expired answer",
                    now - 31L * 86_400_000L)
                archive.appendTurn("synthetic-retention", "synthetic-kept-turn", "synthetic retained utterance", "synthetic retained answer", now)
                archive.appendTurn("synthetic-retention", "synthetic-kept-turn", "synthetic retained utterance",
                    "synthetic retained answer", now, listOf(AgentActionResultRow(
                        "00000000-0000-4000-8000-000000000091", "synthetic-retention", "synthetic-kept-turn",
                        "record_money_event", "APPLIED", "receipt_recorded", "finance",
                        "00000000-0000-4000-8000-000000000092", now)))
                check(archive.setRetentionDays("synthetic-retention", 30, now) == 2)
                check(archive.turn("synthetic-retention", "synthetic-old-turn").isEmpty())
                check(archive.turn("synthetic-retention", "synthetic-kept-turn").size == 2)
                check(archive.actionResults("synthetic-retention", "synthetic-kept-turn").single().status == "APPLIED")
            }
            db.close(); db = null
            val reopenedKey = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
                .addMigrations(AGENT_DB_1_TO_2, AGENT_DB_2_TO_3, AGENT_DB_3_TO_4, AGENT_DB_4_TO_5,
                    AGENT_DB_5_TO_6, AGENT_DB_6_TO_7, AGENT_DB_7_TO_8, AGENT_DB_8_TO_9, AGENT_DB_9_TO_10, AGENT_DB_10_TO_11,
                    AGENT_DB_11_TO_12, AGENT_DB_12_TO_13, AGENT_DB_13_TO_14, AGENT_DB_14_TO_15, AGENT_DB_15_TO_16, AGENT_DB_16_TO_17,
                    AGENT_DB_17_TO_18)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .openHelperFactory(SupportOpenHelperFactory(reopenedKey, null, false)).build()
            db.openHelper.writableDatabase
            verifyCanonicalCaseContinuityProbe(db!!)
            runBlocking {
                val journal = RoomAgentRunJournal(db!!)
                val interrupted = journal.interruptedTurns()
                check(interrupted.single().turnId == "00000000-0000-4000-8000-000000000041")
                check(interrupted.single().status == AgentTurnStatus.EXECUTING)
                journal.setTurnStatus(interrupted.single().turnId, AgentTurnStatus.RECOVERY_REQUIRED)
                check(journal.interruptedTurns().isEmpty())
                check(db!!.protectedStoreDao().turn("00000000-0000-4000-8000-000000000041")?.status == "RECOVERY_REQUIRED")
                check(journal.command("00000000-0000-4000-8000-000000000042")?.status == "reconciling")
                journal.finishTurn("00000000-0000-4000-8000-000000000041", AgentTurnStatus.COMPLETED)
                check(db!!.protectedStoreDao().turn("00000000-0000-4000-8000-000000000041") == null)
                val archive = ProtectedConversationRepository(db!!)
                check(archive.retentionDays("synthetic-retention") == 30)
                check(archive.turn("synthetic-retention", "synthetic-kept-turn").size == 2)
                check(archive.actionResults("synthetic-retention", "synthetic-kept-turn").single().targetId ==
                    "00000000-0000-4000-8000-000000000092")
                val (aliasScope, aliasPersonId, aliasId) = aliasProbe
                val aliases = PersonAliasRepository(db!!)
                check(aliases.list(aliasScope, aliasPersonId).single { it.aliasId == aliasId }.deletedAtMillis == null)
                check(db!!.protectedStoreDao().personAliasRevisions(aliasScope, aliasId).map { it.action } ==
                    listOf("added", "retired", "restored"))
                check(aliases.resolveFinanceAlias(aliasScope, "Synthetic Alias") is FinanceAliasResolution.Resolved)
                val domain = ProtectedDomainRepository(db!!)
                check(domain.search("synthetic-scope", "FILTER").single().version == 4L)
                check(db!!.protectedStoreDao().rows("synthetic-table-p4", "synthetic-scope").size == 2)
                check(domain.aggregateMoney("synthetic-scope", "synthetic-table-p4", "amount") == mapOf("AZN" to 1250L, "USD" to 75L))
                check(domain.calendarBetween("synthetic-scope", "2026-01-05", "2026-01-07").size == 2)
                check(domain.recordSettlement("synthetic-scope", "synthetic-settlement-p6", "synthetic-settle-command", "synthetic-obligation-p6", 2500L, "AZN", null, "synthetic partial payment") == 7500L)
                check(ProtectedMemoryRepository(db!!).tasks().tasks.single { it.id == "synthetic-task-p6" }.title == "Call Elchin")

                val finance = ProtectedDomainRepository(db!!)
                finance.correctFinanceEventAmount("synthetic-finance-context", "synthetic-context-receipt", 8_000L,
                    "synthetic-context-correction", "Synthetic user correction from 100 to 80", "2026-11-03T09:00:00Z")
                val financeHistory = requireNotNull(finance.financeHistory("synthetic-finance-context", "Samir"))
                val receipt = financeHistory.events.single { it.kind == "receipt" }
                val debt = financeHistory.events.single { it.kind == "debt" }
                check(receipt.minorUnits == 8_000L && financeHistory.remainingDebtMinorByEvent[debt.eventId] == 27_000L)
                check(finance.appliedToDebtMinor("synthetic-finance-context", receipt.eventId) == 8_000L)
                val conversation = ProtectedConversationRepository(db!!).recentContext("synthetic-finance-context", 12)
                check(conversation.size == 6 && conversation.any { it.message == "He said this receipt was a partial debt repayment." })
                val searchHandler = LocalMemoryTools(finance, ProtectedMemoryRepository(db!!)).handlers()
                    .single { it.descriptor.name == "search_money" }
                val searchTurn = AgentTurnRequest(UUID.randomUUID().toString(), "synthetic-finance-context", UUID.randomUUID().toString(),
                    null, "az-AZ", "How much remains?", AgentInputChannel.TEXT)
                val searchResult = searchHandler.read(AgentModelToolCall("synthetic-search", "search_money", 1,
                    mapOf("person_name" to "Samir", "query" to "", "kind" to "")), searchTurn)
                val summary = searchResult.modelData["financial_summary"] as? Map<*, *>
                val events = searchResult.modelData["events"] as? List<*>
                val receiptData = events?.mapNotNull { it as? Map<*, *> }?.single { it["event_id"] == receipt.eventId }
                check(searchResult.status == ToolObservationStatus.APPLIED && summary?.get("outstanding_debt_minor_units") == 27_000L)
                check(receiptData?.get("annotations") == listOf("He said this receipt was a partial debt repayment."))

                db!!.protectedStoreDao().putPeople(listOf(
                    PersonRow("synthetic-rashad-one", "Rashad", "synthetic-finance-ambiguous"),
                    PersonRow("synthetic-rashad-two", "Rashad", "synthetic-finance-ambiguous"),
                ))
                val ambiguousRepository = ProtectedDomainRepository(db!!)
                val recordHandler = LocalMemoryTools(ambiguousRepository, ProtectedMemoryRepository(db!!)).handlers()
                    .single { it.descriptor.name == "record_money_event" }
                val beforeAmbiguous = ambiguousRepository.allFinanceEvents("synthetic-finance-ambiguous").size
                val ambiguousPreparation = recordHandler.prepare(AgentModelToolCall("synthetic-ambiguous", "record_money_event", 1,
                    mapOf("kind" to "receipt", "person_name" to "Rashad", "amount" to "50", "currency" to "AZN")),
                    searchTurn.copy(scopeId = "synthetic-finance-ambiguous", text = "Rashad gave me 50 manat"), UUID.randomUUID().toString())
                check(ambiguousPreparation is ToolPreparation.Conflict && ambiguousPreparation.code == "person_name_ambiguous")
                check(ambiguousRepository.allFinanceEvents("synthetic-finance-ambiguous").size == beforeAmbiguous)
            }
            runBlocking {
                check(db!!.protectedStoreDao().moneyEvents().single { it.eventId == "synthetic-receipt" }.minorUnits == 10205L)
                check(db!!.protectedStoreDao().migrations().size == 3)
            }
            db.close(); db = null
            val bytes = databaseFile.readBytes()
            check(!bytes.containsAscii("synthetic migration marker"))
            check(!bytes.contentStartsWith("SQLite format 3\u0000".toByteArray()))
            bytes.fill(0); key.fill(0)
            result.putString("status", "passed")
            result.putBoolean("all_three_stores_migrated_atomically", true)
            result.putBoolean("retry_is_idempotent", true)
            result.putBoolean("task_history_replayed", true)
            result.putBoolean("exact_minor_units_preserved", true)
            result.putBoolean("journal_and_outbox_idempotent", true)
            result.putBoolean("all_legacy_schema_readers_validated", true)
            result.putBoolean("encrypted_legacy_files_read_end_to_end", true)
            result.putBoolean("room_1_to_6_upgrade_preserves_journal", true)
            result.putBoolean("p3_objects_links_delete_restore_and_scoped_search", true)
            result.putBoolean("p4_typed_table_exact_money_export_import", true)
            result.putBoolean("p5_bounded_evidence_search", true)
            result.putBoolean("p6_exact_settlement_task_event_and_calendar_validation", true)
            result.putBoolean("financial_clarification_annotation_correction_and_restart_readback", true)
            result.putBoolean("canonical_case_project_memory_identity_and_exact_finance_survive_sqlcipher_reopen", true)
            result.putBoolean("same_name_conflict_has_no_finance_write", true)
            result.putBoolean("p3_p6_data_survives_database_reopen", true)
            result.putBoolean("room_2_to_3_recovery_journal_survives_reopen", true)
            result.putBoolean("resolved_turn_status_is_removed", true)
            result.putBoolean("closed_database_has_no_plaintext_marker", true)
        } catch (error: Exception) {
            result.putString("status", "failed")
            result.putString("error_type", error.javaClass.simpleName)
            error.message?.filter { it.isLetter() || it == '_' || it == ' ' }?.take(100)?.let { result.putString("failure_reason", it) }
            if (error is SyntheticProbeFailure) {
                result.putString("failure_stage", error.stage)
                error.reason?.let { result.putString("failure_reason", it) }
                error.diagnostic?.let { result.putString("synthetic_import_row_ids", it) }
            }
        } finally {
            runCatching { db?.close() }
            context.deleteDatabase(dbName)
            envelope.baseFile.delete()
            store.deleteEntry(alias)
            sourceFixtureDirectory?.deleteRecursively()
        }
        return result
    }

    private class SyntheticProbeFailure(val stage: String, val reason: String?, val diagnostic: String?) : RuntimeException()

    private fun verifyP3P6Domain(db: AgentDatabase) = runBlocking {
        var stage = "p3-create-objects"
        var diagnostic: String? = null
        try {
        val domain = ProtectedDomainRepository(db)
        domain.createObject("synthetic-scope", "synthetic-person-p3", DomainKind.PERSON, "Nigar", null, null, "2026-01-01T00:00:00Z")
        domain.createObject("synthetic-scope", "synthetic-project-p3", DomainKind.PROJECT, "Repair", null, null, "2026-01-01T00:00:00Z")
        domain.createObject("synthetic-scope", "synthetic-note-p3", DomainKind.NOTE, "Filter service", "Change the filter", "synthetic-source-revision", "2026-01-01T00:00:00Z")
        domain.link("synthetic-scope", "synthetic-link-p3", "synthetic-note-p3", "synthetic-project-p3", "about")
        val updated = domain.updateObject("synthetic-scope", "synthetic-note-p3", 1, "Filter service", "Change the filter tomorrow", "active", "synthetic-source-revision-2", "2026-01-02T00:00:00Z")
        check(updated.version == 2L && db.protectedStoreDao().domainRevisions("synthetic-note-p3").size == 2)
        val searchHit = domain.search("synthetic-scope", "FILTER").single()
        check(searchHit.sourceRevisionId == "synthetic-source-revision-2" && searchHit.evidenceRevisionId == "synthetic-note-p3:2")
        check(searchHit.evidenceEndUtf16 - searchHit.evidenceStartUtf16 == 6)
        check(domain.context("synthetic-scope", "FILTER", 100).single().evidenceText.length <= 100)
        check(domain.search("another-scope", "FILTER").isEmpty())
        val deletion = domain.previewDelete("synthetic-scope", "synthetic-note-p3", 2)
        check(deletion.linkedIds == listOf("synthetic-project-p3"))
        domain.delete("synthetic-scope", deletion, "2026-01-03T00:00:00Z")
        check(domain.search("synthetic-scope", "FILTER").isEmpty())
        check(db.protectedStoreDao().activeLinks("synthetic-scope").isEmpty())
        check(domain.restore("synthetic-scope", "synthetic-note-p3", 3, "2026-01-04T00:00:00Z").version == 4L)
        check(db.protectedStoreDao().activeLinks("synthetic-scope").size == 1)

        stage = "p4-typed-tables"
        stage = "p4-create-table"
        val columns = listOf(ColumnSpec("day", "Day", ColumnType.DATE, true), ColumnSpec("person", "Person", ColumnType.PERSON_REF),
            ColumnSpec("amount", "Amount", ColumnType.MONEY, true), ColumnSpec("memo", "Memo", ColumnType.TEXT))
        domain.createTable("synthetic-scope", "synthetic-table-p4", "Expenses", "editable", columns)
        stage = "p4-add-rows"
        domain.addRows("synthetic-scope", "synthetic-table-p4", 1, listOf(
            "synthetic-row-1" to mapOf("day" to "2026-01-03", "person" to "synthetic-person-p3", "amount" to mapOf("minorUnits" to 1250L, "currency" to "AZN"), "memo" to "filter"),
            "synthetic-row-2" to mapOf("day" to "2026-01-04", "person" to "synthetic-person-p3", "amount" to mapOf("minorUnits" to 75L, "currency" to "USD"), "memo" to "part")
        ))
        stage = "p4-exact-aggregate"
        check(domain.aggregateMoney("synthetic-scope", "synthetic-table-p4", "amount") == mapOf("AZN" to 1250L, "USD" to 75L))
        stage = "p4-export"
        val export = JSONObject(domain.exportTable("synthetic-scope", "synthetic-table-p4"))
        export.put("tableId", "synthetic-table-p4-copy")
        stage = "p4-create-copy"
        domain.createTable("synthetic-scope", "synthetic-table-p4-copy", "Expenses copy", "editable", columns)
        stage = "p4-import-preview"
        val preview = domain.previewImport("synthetic-scope", "synthetic-table-p4-copy", export.toString())
        diagnostic = preview.rows.joinToString(",") { it.first }
        check(preview.rows.size == 2)
        stage = "p4-import-preview-ids"
        check(preview.rows.map { it.first }.distinct().size == 2)
        stage = "p4-import-first"
        check(domain.importRows("synthetic-scope", preview).size == 2)
        check(db.protectedStoreDao().rows("synthetic-table-p4-copy", "synthetic-scope").size == 2)
        stage = "p4-import-retry"
        check(domain.importRows("synthetic-scope", preview).size == 2)
        stage = "p4-schema-change"
        check(domain.changeTableSchema("synthetic-scope", "synthetic-table-p4-copy", 1, columns + ColumnSpec("tag", "Tag", ColumnType.TEXT), "2026-01-05T00:00:00Z") == 2L)
        check(db.protectedStoreDao().schemaRevisions("synthetic-table-p4-copy").map { it.version } == listOf(1L, 2L))
        check(runCatching { domain.addRows("synthetic-scope", "synthetic-table-p4-copy", 1, listOf("stale" to mapOf("day" to "2026-01-05"))) }.isFailure)

        stage = "p6-finance"
        // Finance events are synthetic fixtures; a settlement cannot exceed the exact outstanding balance.
        db.protectedStoreDao().putMoneyEvents(listOf(MoneyEventRow("synthetic-obligation-p6", "synthetic-debt-command", "synthetic-person-p3", "debt", 10_000L, "AZN", "synthetic", "2026-01-01T00:00:00Z", null, null, "synthetic-scope")))
        check(domain.recordSettlement("synthetic-scope", "synthetic-settlement-p6", "synthetic-settle-command", "synthetic-obligation-p6", 2_500L, "AZN", null, "synthetic partial payment") == 7_500L)
        check(domain.recordSettlement("synthetic-scope", "synthetic-settlement-p6", "synthetic-settle-command", "synthetic-obligation-p6", 2_500L, "AZN", null, "synthetic partial payment") == 7_500L)
        check(runCatching { domain.recordSettlement("other-scope", "synthetic-cross-scope-p6", "synthetic-cross-scope-command", "synthetic-obligation-p6", 1L, "AZN", null, "wrong scope") }.isFailure)
        check(runCatching { domain.recordSettlement("synthetic-scope", "synthetic-overpay-p6", "synthetic-overpay-command", "synthetic-obligation-p6", 8_000L, "AZN", null, "synthetic excess") }.isFailure)

        stage = "p6-task-calendar"
        val task = domain.appendTaskEvent(ai.memora.app.domain.TaskEvent.Created("synthetic-task-p6", "synthetic-scope", "synthetic-owner", "synthetic-task-create-p6", "Call Samir"))
        check(task.version == 1L && task.title == "Call Samir")
        check(runCatching { domain.appendTaskEvent(ai.memora.app.domain.TaskEvent.Created("synthetic-task-p6", "synthetic-scope", "synthetic-owner", "synthetic-task-create-p6", "Duplicate")) }.isFailure)
        val renamedTask = domain.appendTaskEvent(ai.memora.app.domain.TaskEvent.Renamed("synthetic-task-p6", "synthetic-scope", "synthetic-owner", "synthetic-task-rename-p6", 1, "Call Elchin"))
        check(renamedTask.version == 2L && renamedTask.title == "Call Elchin")

        val calendar = domain.createCalendarEvent(CalendarInput("synthetic-calendar-p6", "synthetic-scope", "Doctor", "2026-01-05T10:00", "2026-01-05T10:30", "Asia/Baku", "minute"))
        check(calendar.version == 1L)
        check(domain.updateCalendarEvent("synthetic-scope", CalendarInput("synthetic-calendar-p6", "synthetic-scope", "Doctor visit", "2026-01-05T11:00", "2026-01-05T11:30", "Asia/Baku", "minute"), 1).version == 2L)
        check(domain.calendarBetween("synthetic-scope", "2026-01-05T00:00", "2026-01-06T00:00").single().title == "Doctor visit")
        domain.createCalendarEvent(CalendarInput("synthetic-all-day-p6", "synthetic-scope", "Holiday", "2026-01-06", null, "Asia/Baku", "date"))
        check(domain.calendarBetween("synthetic-scope", "2026-01-06", "2026-01-07").single().timePrecision == "date")
        check(runCatching { domain.createCalendarEvent(CalendarInput("synthetic-dst-gap-p6", "synthetic-scope", "DST gap", "2026-03-29T02:30", null, "Europe/Berlin", "minute")) }.isFailure)
        } catch (error: Exception) {
            val safeReason = error.message?.filter { it.isLetter() || it == '_' || it == ' ' }?.take(80)
            throw SyntheticProbeFailure(stage, safeReason, diagnostic)
        }
    }

    /** Synthetic multi-turn ledger path: clarification annotates an existing receipt; a later debt links it once. */
    private fun verifyFinanceClarificationChain(db: AgentDatabase) = runBlocking {
        val scope = "synthetic-finance-context"
        val repository = ProtectedDomainRepository(db)
        val conversations = ProtectedConversationRepository(db)
        val receipt = repository.recordFinanceEvent(scope, "Samir", "receipt", 10_000L, "AZN",
            "Samir gave me 100 manat", null, "synthetic-finance-context-person", "synthetic-context-receipt",
            "synthetic-context-receipt-command", "2026-11-03T08:00:00Z", "2026-10-30T08:00:00Z")
        conversations.appendTurn(scope, "synthetic-context-turn-1", "Samir gave me 100 manat", "Saved one receipt.", 1L)

        val clarification = "He said this receipt was a partial debt repayment."
        val annotation = repository.annotateFinanceEvent(scope, receipt.eventId, "synthetic-context-annotation",
            clarification, "2026-11-03T08:01:00Z")
        val duplicateAnnotation = repository.annotateFinanceEvent(scope, receipt.eventId, "synthetic-context-annotation-retry",
            clarification, "2026-11-03T08:02:00Z")
        check(annotation == duplicateAnnotation && annotation.kind == "annotation" && annotation.minorUnits == 0L)
        conversations.appendTurn(scope, "synthetic-context-turn-2", clarification, "I attached that context to the saved receipt.", 2L)

        val debt = repository.recordFinanceEvent(scope, "Samir", "debt", 35_000L, "AZN",
            "Earlier, Samir owed me 350 manat", null, "synthetic-finance-context-person", "synthetic-context-debt",
            "synthetic-context-debt-command", "2026-11-03T08:03:00Z", "2026-10-29T08:00:00Z")
        val remainingBeforeRestart = repository.recordSettlement(scope, "synthetic-context-settlement",
            "synthetic-context-settlement-command", debt.eventId, 10_000L, "AZN", "2026-10-30T08:00:00Z",
            "Synthetic partial repayment", receipt.eventId)
        check(remainingBeforeRestart == 25_000L)
        check(repository.financeHistory(scope, "Samir")?.events?.count { it.kind == "receipt" } == 1)
        conversations.appendTurn(scope, "synthetic-context-turn-3", "Earlier he owed me 350 manat.",
            "The existing repayment is linked to that debt.", 3L)
    }

    /** Synthetic migration/reopen proof for explicit aliases and the existing owner-confirmed ledger bridge. */
    private fun verifyPersonAliasChain(db: AgentDatabase): Triple<String, String, String> = runBlocking {
        val scope = "synthetic-person-alias-migration"
        val personId = "synthetic-alias-domain-person"
        val domain = ProtectedDomainRepository(db)
        domain.createObject(scope, personId, DomainKind.PERSON, "Synthetic Canonical Person", null, null,
            "2026-11-03T10:00:00Z")
        domain.recordFinanceEvent(scope, "Synthetic Ledger Person", "receipt", 100L, "AZN", "synthetic alias probe",
            null, "synthetic-alias-finance-person", "synthetic-alias-finance-event", "synthetic-alias-finance-command",
            "2026-11-03T10:01:00Z", "2026-11-03T10:01:00Z")
        val aliases = PersonAliasRepository(db)
        val saved = aliases.add(scope, personId, "Synthetic Alias", actorId = "owner",
            sourceTurnId = "synthetic-alias-source-turn", nowMillis = 100)
        check(aliases.resolveFinanceAlias(scope, "synthetic alias") == FinanceAliasResolution.OwnerLinkRequired)
        ProtectedCaseRepository(db).confirmFinancePersonIdentity(scope, "synthetic-alias-finance-person", personId,
            confirmedBy = "owner", now = "2026-11-03T10:02:00Z")
        check(aliases.resolveFinanceAlias(scope, "Synthetic Alias") is FinanceAliasResolution.Resolved)
        val retired = aliases.retire(scope, saved.aliasId, saved.version, nowMillis = 200)
        aliases.restore(scope, retired.aliasId, retired.version, nowMillis = 300)
        Triple(scope, personId, saved.aliasId)
    }

    /** Synthetic cross-domain BMW case with owner-confirmed identity and canonical, non-copied sources. */
    private fun seedCanonicalCaseContinuityProbe(db: AgentDatabase) = runBlocking {
        val scope = "synthetic-bmw-case-continuity"
        val domain = ProtectedDomainRepository(db)
        domain.createObject(scope, "synthetic-bmw-domain-person", DomainKind.PERSON, "Synthetic Elchin", null, null,
            "2026-11-03T09:00:00Z")
        domain.createObject(scope, "synthetic-bmw-project", DomainKind.PROJECT, "Synthetic BMW repair", "Fictional project source", null,
            "2026-11-03T09:01:00Z")
        val debt = domain.recordFinanceEvent(scope, "Synthetic Elchin", "debt", 30_000L, "AZN", "synthetic agreed repair total", null,
            "synthetic-bmw-finance-person", "synthetic-bmw-obligation", "synthetic-bmw-obligation-command",
            "2026-11-03T09:02:00Z", "2026-11-03T09:02:00Z")
        val receipt = domain.recordFinanceEvent(scope, "Synthetic Elchin", "receipt", 10_000L, "AZN", "synthetic advance received", null,
            "synthetic-bmw-finance-person", "synthetic-bmw-receipt", "synthetic-bmw-receipt-command",
            "2026-11-03T09:03:00Z", "2026-11-03T09:03:00Z")
        check(domain.recordSettlement(scope, "synthetic-bmw-settlement", "synthetic-bmw-settlement-command", debt.eventId,
            10_000L, "AZN", "2026-11-03T09:03:00Z", "synthetic advance", receipt.eventId) == 20_000L)
        db.protectedStoreDao().putSources(listOf(MemorySourceRow("synthetic-bmw-memory", "synthetic-bmw-memory-command",
            null, "Asia/Baku", "voice", false, 1, scope)))
        db.protectedStoreDao().putRevisions(listOf(MemoryRevisionRow("synthetic-bmw-memory", "synthetic-bmw-memory-revision",
            1, "Synthetic note: replacement parts arrive Friday.", "2026-11-03T09:04:00Z")))

        val cases = ProtectedCaseRepository(db)
        cases.confirmFinancePersonIdentity(scope, "synthetic-bmw-finance-person", "synthetic-bmw-domain-person",
            confirmedBy = "owner", now = "2026-11-03T09:05:00Z")
        cases.createCase(scope, "synthetic-bmw-case", "Synthetic BMW repair", "Fictional repair details",
            nextAction = "Wait for parts", sourceTurnId = "synthetic-bmw-source-turn", now = "2026-11-03T09:06:00Z")
        cases.addParticipant(scope, "synthetic-bmw-case", "synthetic-bmw-domain-person", role = "customer",
            participantId = "synthetic-bmw-participant", now = "2026-11-03T09:07:00Z")
        cases.addEvidence(scope, "synthetic-bmw-case", "domain_object", "synthetic-bmw-project", "repair_project",
            evidenceId = "synthetic-bmw-project-evidence", now = "2026-11-03T09:08:00Z")
        cases.addEvidence(scope, "synthetic-bmw-case", "memory_revision", "synthetic-bmw-memory", "repair_note",
            sourceRevisionId = "synthetic-bmw-memory-revision", evidenceId = "synthetic-bmw-memory-evidence",
            now = "2026-11-03T09:09:00Z")
        cases.addEvidence(scope, "synthetic-bmw-case", "money_event", debt.eventId, "agreed_total",
            evidenceId = "synthetic-bmw-obligation-evidence", now = "2026-11-03T09:10:00Z")
        cases.addEvidence(scope, "synthetic-bmw-case", "money_event", receipt.eventId, "advance_received",
            evidenceId = "synthetic-bmw-receipt-evidence", now = "2026-11-03T09:11:00Z")
        verifyCanonicalCaseContinuityProbe(db)
    }

    /** Re-run before and after reopening the SQLCipher file to prove canonical case-source continuity. */
    private fun verifyCanonicalCaseContinuityProbe(db: AgentDatabase) = runBlocking {
        val scope = "synthetic-bmw-case-continuity"
        val snapshot = requireNotNull(ProtectedCaseRepository(db).caseSnapshot(scope, "synthetic-bmw-case"))
        check(snapshot.participants.single().objectId == "synthetic-bmw-domain-person")
        check(snapshot.record.title == "Synthetic BMW repair" && snapshot.record.nextAction == "Wait for parts")
        check(snapshot.evidence.size == 4 && snapshot.evidence.all { it.sourceAvailable })
        check(snapshot.evidence.any { it.evidence.sourceKind == "domain_object" &&
            it.evidence.sourceId == "synthetic-bmw-project" && it.title == "Synthetic BMW repair" })
        check(snapshot.evidence.any { it.evidence.sourceKind == "memory_revision" &&
            it.evidence.sourceId == "synthetic-bmw-memory" && it.evidence.sourceRevisionId == "synthetic-bmw-memory-revision" &&
            it.detail == "Synthetic note: replacement parts arrive Friday." })
        check(snapshot.timeline.count { it.sourceKind == "money_event" } == 2)
        val domain = ProtectedDomainRepository(db)
        check(domain.outstandingDebtMinor(scope, "synthetic-bmw-obligation") == 20_000L)
        val history = requireNotNull(domain.financeHistory(scope, "Synthetic Elchin"))
        check(history.events.count { it.kind == "receipt" } == 1 && history.events.count { it.kind == "debt" } == 1)
        check(history.events.single { it.kind == "receipt" }.minorUnits == 10_000L)
        check(history.events.single { it.kind == "debt" }.minorUnits == 30_000L)
        check(ProtectedCaseRepository(db).financePersonIdentityChoices(scope, "synthetic-bmw-finance-person")
            ?.confirmedLink?.domainPersonId == "synthetic-bmw-domain-person")
        check(db.protectedStoreDao().activeDomainObjects(scope, DomainKind.PROJECT.name, 10)
            .count { it.objectId == "synthetic-bmw-project" } == 1)
        check(db.protectedStoreDao().sources().count { it.sourceId == "synthetic-bmw-memory" } == 1)
        check(db.protectedStoreDao().revisions().count { it.sourceId == "synthetic-bmw-memory" } == 1)
    }

    private fun createMigrationCoordinator(
        context: Context,
        repository: ProtectedMemoryRepository,
    ): Pair<LegacyStoreMigrationCoordinator, File> {
        val directory = File(context.cacheDir, "memora-synthetic-migration-inputs")
        directory.deleteRecursively(); check(directory.mkdirs())
        val captureKey = SecretKeySpec(ByteArray(32) { (it + 3).toByte() }, "AES")
        val taskKey = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES")
        val moneyKey = SecretKeySpec(ByteArray(32) { (it + 19).toByte() }, "AES")
        val date = "2026-01-02T03:04:05Z"

        val captureFile = File(directory, "captures.bin")
        val capture = JSONObject().put("schema", 5).put("draft", "synthetic draft").put("draftVersion", 2)
            .put("captures", JSONArray().put(JSONObject().put("command", "capture-command").put("draftVersion", 1)
                .put("zone", "Asia/Baku").put("source", "synthetic-source").put("origin", "LOCAL")
                .put("archived", false).put("organizationVersion", 0)
                .put("revisions", JSONArray().put(JSONObject().put("id", "synthetic-revision").put("number", 1)
                    .put("text", "synthetic migration marker v1").put("recordedAt", date))
                    .put(JSONObject().put("id", "synthetic-revision-2").put("number", 2)
                        .put("text", "synthetic migration marker v2").put("recordedAt", date)))))
            .put("deleted", JSONArray().put(JSONObject().put("command", "deleted-command")
                .put("source", "deleted-source").put("revision", "deleted-revision")
                .put("priorRevisions", JSONArray().put("deleted-prior-revision"))))
        captureFile.writeBytes(encryptLegacy(byteArrayOf(0x4d, 0x43, 0x01), capture.toString(), captureKey))

        val taskFile = File(directory, "tasks.bin")
        val events = JSONArray()
            .put(JSONObject().put("id", "synthetic-task").put("scope", "local").put("actor", "owner")
                .put("command", "synthetic-command-1").put("type", "created").put("title", "Call the dentist"))
            .put(JSONObject().put("id", "synthetic-task").put("scope", "local").put("actor", "owner")
                .put("command", "synthetic-command-2").put("type", "renamed").put("version", 1).put("title", "Call dentist"))
        taskFile.writeBytes(encryptLegacy(byteArrayOf(0x4d, 0x54, 0x01), JSONObject().put("schema", 2)
            .put("events", events).put("deleted", JSONArray().put("deleted-task-marker")).toString(), taskKey))

        val moneyFile = File(directory, "voice-money.bin")
        val money = JSONObject().put("schema", 2)
            .put("people", JSONArray().put(JSONObject().put("id", "synthetic-person").put("name", "Nigar")))
            .put("receipts", JSONArray().put(JSONObject().put("id", "synthetic-receipt").put("command", "synthetic-command-3")
                .put("person", "synthetic-person").put("minor", 10205).put("currency", "AZN")
                .put("source", "synthetic spoken source").put("recorded", date).put("occurred", JSONObject.NULL)))
            .put("debts", JSONArray())
        moneyFile.writeBytes(encryptLegacy(byteArrayOf(0x4d, 0x56, 0x01), money.toString(), moneyKey))

        return LegacyStoreMigrationCoordinator(
            captureStore = EncryptedCaptureStore(captureFile) { captureKey },
            taskStore = EncryptedTaskStore(taskFile) { taskKey },
            voiceMoneyStore = VoiceMoneyStore(moneyFile) { moneyKey },
            encryptedFiles = mapOf("captures" to captureFile, "tasks" to taskFile, "voice-money" to moneyFile),
            destination = repository,
        ) to directory
    }

    private fun verifyRoomSchemaUpgrade(context: Context, keyStore: KeyStore) {
        val dbName = "memora-agent-schema-upgrade-probe.db"
        val alias = "ai.memora.app.test.schema-upgrade-probe.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "memora-agent-schema-upgrade-probe-key.v1"))
        val file = context.getDatabasePath(dbName)
        try {
            val oldKey = DatabaseKeyProvider(context, file, envelope, keyStore, alias).loadOrCreate()
            val v1 = Room.databaseBuilder(context, ProtectedProbeDatabase::class.java, dbName)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .openHelperFactory(SupportOpenHelperFactory(oldKey, null, false)).build()
            v1.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-v1-command", "1".repeat(64), "pending", "[]"),
            )
            v1.close(); oldKey.fill(0)

            val upgradedKey = DatabaseKeyProvider(context, file, envelope, keyStore, alias).loadOrCreate()
            val upgraded = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
                .addMigrations(AGENT_DB_1_TO_2, AGENT_DB_2_TO_3, AGENT_DB_3_TO_4, AGENT_DB_4_TO_5,
                    AGENT_DB_5_TO_6, AGENT_DB_6_TO_7, AGENT_DB_7_TO_8, AGENT_DB_8_TO_9, AGENT_DB_9_TO_10, AGENT_DB_10_TO_11,
                    AGENT_DB_11_TO_12, AGENT_DB_12_TO_13, AGENT_DB_13_TO_14, AGENT_DB_14_TO_15, AGENT_DB_15_TO_16, AGENT_DB_16_TO_17,
                    AGENT_DB_17_TO_18)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .openHelperFactory(SupportOpenHelperFactory(upgradedKey, null, false)).build()
            upgraded.openHelper.writableDatabase
            runBlocking {
                val schemaVersion = upgraded.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                check(schemaVersion == 18)
                check(upgraded.protectedStoreDao().command("synthetic-v1-command")?.status == "pending")
                check(upgraded.protectedStoreDao().migrations().isEmpty())
                val linkTables = upgraded.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN (?, ?, ?, ?) ORDER BY name",
                    arrayOf("calendar_person_link", "calendar_person_link_revision", "task_person_link", "task_person_link_revision"),
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                check(linkTables == listOf("calendar_person_link", "calendar_person_link_revision", "task_person_link", "task_person_link_revision"))
                for (table in listOf("calendar_person_link", "calendar_person_link_revision", "task_person_link", "task_person_link_revision")) {
                    check(upgraded.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                        check(cursor.moveToFirst()); cursor.getInt(0) == 0
                    })
                }
                val migratedProfile = requireNotNull(upgraded.protectedStoreDao().localProfile())
                check(runCatching { UUID.fromString(migratedProfile.localProfileId) }.isSuccess)
                check(migratedProfile.toneStyle == "friendly" && migratedProfile.responseStyle == "balanced")
                check(migratedProfile.onboardingCompleted && migratedProfile.onboardingVersion == 1)
                check(migratedProfile.remoteAccountId == null && migratedProfile.displayName == null)
                check(upgraded.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='agent_turn_journal'").use { it.moveToFirst() })
                for (table in listOf("person_identity_link", "case_record", "case_revision", "case_participant",
                        "case_evidence", "conversation_retention", "agent_action_result", "conversation_episode",
                        "conversation_episode_revision", "conversation_episode_source", "profile_fact",
                        "profile_fact_source", "profile_fact_revision", "person_alias", "person_alias_revision")) {
                    check(upgraded.openHelper.readableDatabase.query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table),
                    ).use { it.moveToFirst() })
                }
                val profileRepository = ProtectedProfileMemoryRepository(upgraded)
                val profileScope = UUID.randomUUID().toString()
                val profileSource = UUID.randomUUID().toString()
                val profileCorrectionSource = UUID.randomUUID().toString()
                val profileFact = profileRepository.saveUserStated(profileScope, "preference",
                    "Synthetic profile memory prefers concise replies", profileSource,
                    "Synthetic: I prefer concise replies.", nowMillis = 1_800_000_000_000L)
                check(profileFact.revision == 1L && profileFact.sourceTurnIds == listOf(profileSource))
                val correctedFact = profileRepository.correctFromUser(profileScope, profileFact.factId, 1,
                    "preference", "Synthetic profile memory prefers detailed replies", profileCorrectionSource,
                    "Synthetic correction: detailed replies are better.", nowMillis = 1_800_000_001_000L)
                check(correctedFact.revision == 2L && correctedFact.revisions.size == 2)
                check(correctedFact.revisions.last().sourceTurnId == profileCorrectionSource)
                check(ProtectedConversationRepository(upgraded).deleteTurn(profileScope, profileSource))
                check(profileRepository.get(profileScope, profileFact.factId) == null)
            }
            upgraded.close(); upgradedKey.fill(0)
        } finally {
            context.deleteDatabase(dbName)
            envelope.baseFile.delete()
            keyStore.deleteEntry(alias)
        }
    }

    private fun verifyLegacyStoreReaders(context: Context) {
        val directory = File(context.cacheDir, "memora-synthetic-legacy-fixtures")
        directory.deleteRecursively(); check(directory.mkdirs())
        val testKey = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
        try {
            for (schema in 1..5) {
                val capture = JSONObject().put("schema", schema).put("draft", "synthetic draft")
                    .put("draftVersion", 2)
                val captureItem = JSONObject().put("command", "capture-command-$schema").put("draftVersion", 1)
                    .put("zone", "Asia/Baku").put("source", "capture-source-$schema")
                if (schema < 5) captureItem.put("revision", "capture-revision-$schema")
                    .put("text", "legacy source $schema").put("recordedAt", "2026-01-02T03:04:05Z")
                else captureItem.put("revisions", JSONArray().put(JSONObject().put("id", "capture-revision-$schema")
                    .put("number", 1).put("text", "legacy source $schema").put("recordedAt", "2026-01-02T03:04:05Z")))
                if (schema >= 3) captureItem.put("origin", "LOCAL")
                if (schema >= 4) captureItem.put("archived", false).put("organizationVersion", 0)
                capture.put("captures", JSONArray().put(captureItem))
                if (schema >= 2) capture.put("deleted", JSONArray().put(JSONObject()
                    .put("command", "deleted-command-$schema").put("source", "deleted-source-$schema")
                    .put("revision", "deleted-revision-$schema").put("priorRevisions", JSONArray())))
                val captureFile = File(directory, "captures-$schema.bin")
                captureFile.writeBytes(encryptLegacy(byteArrayOf(0x4d, 0x43, 0x01), capture.toString(), testKey))
                val captureStore = EncryptedCaptureStore(captureFile) { testKey }
                check((captureStore.load() as? CaptureRead.Ready)?.snapshot?.captures?.single()?.source?.id == "capture-source-$schema")

                if (schema <= 2) {
                    val event = JSONObject().put("id", "task-$schema").put("scope", "local").put("actor", "owner")
                        .put("command", "task-command-$schema").put("type", "created").put("title", "Synthetic task")
                    val taskDocument = if (schema == 1) JSONArray().put(event).toString() else JSONObject().put("schema", 2)
                        .put("events", JSONArray().put(event)).put("deleted", JSONArray().put("deleted-task-$schema")).toString()
                    val taskFile = File(directory, "tasks-$schema.bin")
                    taskFile.writeBytes(encryptLegacy(byteArrayOf(0x4d, 0x54, 0x01), taskDocument, testKey))
                    val taskStore = EncryptedTaskStore(taskFile) { testKey }
                    check((taskStore.load() as? StoreRead.Ready)?.tasks?.singleOrNull()?.id == "task-$schema")
                    if (schema == 2) check(taskStore.migrationSnapshot().deletedIds == setOf("deleted-task-$schema"))
                }
            }

            for (schema in 1..2) {
                val people = JSONArray().put(JSONObject().put("id", "person-$schema").put("name", "Aysel"))
                val receipts = JSONArray().put(JSONObject().put("id", "receipt-$schema").put("command", "receipt-command-$schema")
                    .put("person", "person-$schema").put("minor", 8250).put("currency", "AZN")
                    .put("source", "synthetic receipt source").put("recorded", "2026-01-02T03:04:05Z").put("occurred", JSONObject.NULL))
                val voice = JSONObject().put("schema", schema).put("people", people).put("receipts", receipts)
                if (schema == 2) voice.put("debts", JSONArray().put(JSONObject().put("id", "debt-$schema")
                    .put("command", "debt-command-$schema").put("person", "person-$schema").put("minor", 1200)
                    .put("currency", "AZN").put("source", "synthetic debt source").put("recorded", "2026-01-02T03:04:05Z")))
                val voiceFile = File(directory, "voice-$schema.bin")
                voiceFile.writeBytes(encryptLegacy(byteArrayOf(0x4d, 0x56, 0x01), voice.toString(), testKey))
                val voiceStore = VoiceMoneyStore(voiceFile) { testKey }
                val snapshot = (voiceStore.load() as? VoiceStoreRead.Ready)?.snapshot ?: error("Voice schema $schema rejected")
                check(snapshot.receipts.single().minorUnits == 8250L)
                check(snapshot.debts.size == if (schema == 1) 0 else 1)
            }
        } finally {
            directory.deleteRecursively()
            testKey.encoded.fill(0)
        }
    }

    private fun encryptLegacy(header: ByteArray, json: String, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        return header + cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }

    private fun open(context: Context, name: String, key: ByteArray): ProtectedProbeDatabase =
        Room.databaseBuilder(context, ProtectedProbeDatabase::class.java, name)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .openHelperFactory(SupportOpenHelperFactory(key, null, false))
            .build()

    private fun readCommandId(database: ProtectedProbeDatabase): String =
        database.openHelper.readableDatabase.query(
            "SELECT commandId FROM agent_command_journal WHERE commandId = 'synthetic-probe-command'",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun hasCommand(database: ProtectedProbeDatabase, commandId: String): Boolean =
        database.openHelper.readableDatabase.query(
            "SELECT commandId FROM agent_command_journal WHERE commandId = ?",
            arrayOf(commandId),
        ).use { it.moveToFirst() }

    private fun verifyMissingWrappingKeyFailsClosed(context: Context): Boolean {
        val name = "memora-agent-probe-lost-wrap-key.db"
        val alias = "ai.memora.app.test.lost-wrap-key.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val databaseFile = context.getDatabasePath(name)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: ProtectedProbeDatabase? = null
        try {
            val key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-key-loss-marker", "b".repeat(64), "COMMITTED", "[]"),
            )
            database.close(); database = null; key.fill(0)
            val databaseBefore = databaseFile.readBytes()
            val envelopeBefore = envelope.baseFile.readBytes()
            store.deleteEntry(alias)
            val rejected = runCatching {
                DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            }.exceptionOrNull() is DatabaseKeyUnavailableException
            val unchanged = databaseFile.readBytes().contentEquals(databaseBefore) &&
                envelope.baseFile.readBytes().contentEquals(envelopeBefore)
            databaseBefore.fill(0); envelopeBefore.fill(0)
            check(rejected && unchanged)
            return true
        } finally {
            runCatching { database?.close() }
            context.deleteDatabase(name)
            envelope.baseFile.delete()
            store.deleteEntry(alias)
        }
    }

    /** Exercises staged-key crash recovery with a disposable synthetic DB, never the app's user DB. */
    private fun verifySyntheticKeyRotationRecovery(context: Context): Boolean {
        val name = "memora-agent-probe-key-rotation.db"
        val alias = "ai.memora.app.test.key-rotation.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val databaseFile = context.getDatabasePath(name)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val provider = DatabaseKeyProvider(context, databaseFile, envelope, store, alias)
        var database: AgentDatabase? = null
        var currentKey: ByteArray? = null
        var nextKey: ByteArray? = null
        var refusedOldKey = false
        try {
            currentKey = provider.loadOrCreate()
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            database!!.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-key-rotation-marker", "d".repeat(64), "COMMITTED", "[]"),
            )

            // Simulate interruption after staging but before rekey: the exact app startup path keeps the old key.
            val abandonedCandidate = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            provider.stageRotation(abandonedCandidate)
            database!!.close(); database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            check(provider.loadStagedRotation() == null)
            val startupKey = provider.loadOrCreate()
            val stillCurrent = startupKey.contentEquals(currentKey!!)
            startupKey.fill(0)
            abandonedCandidate.fill(0)
            check(stillCurrent)
            database!!.close(); database = null

            nextKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            provider.stageRotation(nextKey!!)
            val cipherDatabase = database!!.openHelper.writableDatabase as net.zetetic.database.sqlcipher.SQLiteDatabase
            cipherDatabase.changePassword(nextKey!!)
            database!!.close(); database = null

            refusedOldKey = runCatching {
                val oldDatabase = AgentDatabase.openWithKeyForSyntheticProbe(context, name, currentKey!!)
                try { oldDatabase.openHelper.readableDatabase.query("SELECT commandId FROM agent_command_journal").use { it.moveToFirst() } }
                finally { oldDatabase.close() }
            }.isFailure
            check(refusedOldKey)

            // This is the app's actual startup reconciliation after rekey-before-envelope-promotion.
            database = AgentDatabase.openForSyntheticProbe(context, provider, name)
            val marker = database!!.openHelper.readableDatabase.query(
                "SELECT commandId FROM agent_command_journal WHERE commandId = 'synthetic-key-rotation-marker'",
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
            check(marker == "synthetic-key-rotation-marker")
            database!!.close(); database = null

            val promotedKey = provider.loadOrCreate()
            val promotedMatches = promotedKey.contentEquals(nextKey!!)
            promotedKey.fill(0)
            check(promotedMatches && provider.loadStagedRotation() == null)
            return true
        } finally {
            runCatching { database?.close() }
            currentKey?.fill(0)
            nextKey?.fill(0)
            context.deleteDatabase(name)
            envelope.baseFile.delete()
            File(envelope.baseFile.path + ".bak").delete()
            File(envelope.baseFile.path + ".rotation").delete()
            store.deleteEntry(alias)
        }
    }

    private fun verifyMissingEnvelopeFailsClosed(context: Context): Boolean {
        val name = "memora-agent-probe-lost-envelope.db"
        val alias = "ai.memora.app.test.lost-envelope.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val databaseFile = context.getDatabasePath(name)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: ProtectedProbeDatabase? = null
        try {
            val key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-envelope-loss-marker", "c".repeat(64), "COMMITTED", "[]"),
            )
            database.close(); database = null; key.fill(0)
            val databaseBefore = databaseFile.readBytes()
            envelope.baseFile.delete()
            val rejected = runCatching {
                DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            }.exceptionOrNull() is DatabaseKeyUnavailableException
            val unchanged = databaseFile.readBytes().contentEquals(databaseBefore) && !envelope.baseFile.exists()
            databaseBefore.fill(0)
            check(rejected && unchanged)
            return true
        } finally {
            runCatching { database?.close() }
            context.deleteDatabase(name)
            envelope.baseFile.delete()
            store.deleteEntry(alias)
        }
    }

    private fun verifyCorruptDatabaseFailsClosed(context: Context): Boolean {
        val name = "memora-agent-probe-corrupt-db.db"
        val alias = "ai.memora.app.test.corrupt-db.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val databaseFile = context.getDatabasePath(name)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: ProtectedProbeDatabase? = null
        var key: ByteArray? = null
        var original: ByteArray? = null
        var corrupted: ByteArray? = null
        try {
            key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-corruption-marker", "f".repeat(64), "COMMITTED", "[]"),
            )
            database.close(); database = null
            original = databaseFile.readBytes()
            corrupted = original!!.copyOf().also { bytes ->
                check(bytes.size > 512)
                val offset = bytes.size / 2
                bytes[offset] = (bytes[offset].toInt() xor 0x5a).toByte()
            }
            databaseFile.writeBytes(corrupted!!)
            val openAttempt = runCatching {
                val corruptDatabase = open(context, name, key!!)
                try {
                    check(hasCommand(corruptDatabase, "synthetic-corruption-marker"))
                } finally {
                    corruptDatabase.close()
                }
            }
            val corruptionWasRejected = openAttempt.isFailure
            val corruptedBytesStayedUnchanged = databaseFile.readBytes().useBytes { it.contentEquals(corrupted!!) }
            check(corruptionWasRejected && corruptedBytesStayedUnchanged)

            databaseFile.writeBytes(original!!)
            database = open(context, name, key!!)
            val originalDataSurvived = database.openHelper.readableDatabase.query(
                "SELECT commandId FROM agent_command_journal WHERE commandId = 'synthetic-corruption-marker'",
            ).use { it.moveToFirst() }
            check(originalDataSurvived)
            return true
        } finally {
            runCatching { database?.close() }
            original?.fill(0)
            corrupted?.fill(0)
            key?.fill(0)
            context.deleteDatabase(name)
            envelope.baseFile.delete()
            store.deleteEntry(alias)
        }
    }

    private fun verifyCorruptEnvelopeFailsClosed(context: Context): Boolean {
        val name = "memora-agent-probe-corrupt-envelope.db"
        val alias = "ai.memora.app.test.corrupt-envelope.v1"
        val envelope = AtomicFile(File(context.noBackupFilesDir, "$name.envelope"))
        val databaseFile = context.getDatabasePath(name)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: ProtectedProbeDatabase? = null
        var key: ByteArray? = null
        var originalEnvelope: ByteArray? = null
        var corruptedEnvelope: ByteArray? = null
        var databaseBefore: ByteArray? = null
        try {
            key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO agent_command_journal (commandId, digest, status, targetVersionsJson) VALUES (?, ?, ?, ?)",
                arrayOf("synthetic-envelope-corruption-marker", "a".repeat(64), "COMMITTED", "[]"),
            )
            database.close(); database = null; key!!.fill(0); key = null
            databaseBefore = databaseFile.readBytes()
            originalEnvelope = envelope.baseFile.readBytes()
            corruptedEnvelope = originalEnvelope!!.copyOf().also { bytes ->
                val offset = bytes.lastIndex
                bytes[offset] = (bytes[offset].toInt() xor 0x5a).toByte()
            }
            envelope.baseFile.writeBytes(corruptedEnvelope!!)
            val rejected = runCatching {
                DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            }.exceptionOrNull() is DatabaseKeyUnavailableException
            val dbUnchanged = databaseFile.readBytes().useBytes { it.contentEquals(databaseBefore!!) }
            val envelopeStillTampered = envelope.baseFile.readBytes().useBytes { it.contentEquals(corruptedEnvelope!!) }
            check(rejected && dbUnchanged && envelopeStillTampered)

            envelope.baseFile.writeBytes(originalEnvelope!!)
            key = DatabaseKeyProvider(context, databaseFile, envelope, store, alias).loadOrCreate()
            database = open(context, name, key)
            val dataSurvived = hasCommand(database, "synthetic-envelope-corruption-marker")
            check(dataSurvived)
            return true
        } finally {
            runCatching { database?.close() }
            key?.fill(0)
            originalEnvelope?.fill(0)
            corruptedEnvelope?.fill(0)
            databaseBefore?.fill(0)
            context.deleteDatabase(name)
            envelope.baseFile.delete()
            store.deleteEntry(alias)
        }
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.toByteArray(Charsets.UTF_8)
        return indices.any { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }
    }

    private inline fun <T> ByteArray.useBytes(block: (ByteArray) -> T): T = try {
        block(this)
    } finally {
        fill(0)
    }

    private fun ByteArray.contentStartsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    @androidx.room.Database(entities = [AgentCommandJournalRow::class], version = 1, exportSchema = false)
    abstract class ProtectedProbeDatabase : androidx.room.RoomDatabase()
}

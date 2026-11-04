package ai.memora.app.data.protectedstore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates or unwraps the SQLCipher key using a separate non-exportable Android Keystore key.
 * Existing database/key-envelope inconsistencies fail closed; this class never resets either.
 */
internal class DatabaseKeyProvider(
    context: Context,
    internal val databaseFile: File = context.getDatabasePath(DATABASE_NAME),
    private val envelopeFile: AtomicFile = AtomicFile(File(context.noBackupFilesDir, KEY_ENVELOPE_NAME)),
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
    private val wrappingKeyAlias: String = KEY_ALIAS,
    private val stagedEnvelopeFile: AtomicFile = AtomicFile(
        File(envelopeFile.baseFile.parentFile, "${envelopeFile.baseFile.name}.rotation"),
    ),
    private val portableRestoreEnvelopeFile: AtomicFile = AtomicFile(
        File(envelopeFile.baseFile.parentFile, "${envelopeFile.baseFile.name}.portable-restore"),
    ),
    private val portableRestoreRollbackEnvelopeFile: AtomicFile = AtomicFile(
        File(envelopeFile.baseFile.parentFile, "${envelopeFile.baseFile.name}.portable-rollback"),
    ),
) {
    fun loadOrCreate(): ByteArray = synchronized(PROCESS_LOCK) {
        if (hasAtomicFileContents(envelopeFile)) return@synchronized unwrapExistingEnvelope()
        if (databaseFile.exists()) {
            throw DatabaseKeyUnavailableException("Encrypted database exists without its key envelope")
        }

        val wrappingKey = getOrCreateWrappingKey()
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(java.security.SecureRandom()::nextBytes)
        val encrypted = encrypt(wrappingKey, databaseKey)
        try {
            writeEnvelope(envelopeFile, encrypted, "Could not persist protected database key")
        } catch (error: Exception) {
            databaseKey.fill(0)
            throw error
        } finally {
            encrypted.fill(0)
        }
        databaseKey
    }

    private fun unwrapExistingEnvelope(): ByteArray {
        return unwrapEnvelope(envelopeFile)
    }

    /** A staged key is durable before SQLCipher rekey and lets startup recover a rekeyed DB. */
    fun stageRotation(databaseKey: ByteArray) = synchronized(PROCESS_LOCK) {
        require(databaseKey.size == DATABASE_KEY_BYTES)
        check(hasAtomicFileContents(envelopeFile)) { "Cannot rotate a database without a current key envelope" }
        val encrypted = encrypt(getExistingWrappingKey(), databaseKey)
        try {
            writeEnvelope(stagedEnvelopeFile, encrypted, "Could not persist staged database key")
        } finally {
            encrypted.fill(0)
        }
    }

    fun loadStagedRotation(): ByteArray? = synchronized(PROCESS_LOCK) {
        if (!hasAtomicFileContents(stagedEnvelopeFile)) return@synchronized null
        unwrapEnvelope(stagedEnvelopeFile).also {
            if (it.size != DATABASE_KEY_BYTES) {
                it.fill(0)
                throw DatabaseKeyUnavailableException("Staged database rotation key has invalid length")
            }
        }
    }

    /** Install a staged envelope atomically; keep the staged copy until AtomicFile has committed. */
    fun promoteStagedRotation() = synchronized(PROCESS_LOCK) {
        val key = loadStagedRotation() ?: throw DatabaseKeyUnavailableException("No staged database key")
        key.fill(0)
        val bytes = try {
            stagedEnvelopeFile.openRead().use { it.readBytes() }
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException("Could not read staged database key envelope", error)
        }
        try {
            writeEnvelope(envelopeFile, bytes, "Could not promote staged database key")
            stagedEnvelopeFile.delete()
        } finally {
            bytes.fill(0)
        }
    }

    /** Treat startWrite, short writes, fsync, and finishWrite failures as one fail-closed operation. */
    private fun writeEnvelope(file: AtomicFile, bytes: ByteArray, failureMessage: String) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
            output = null
            syncEnvelopeDirectory(file.baseFile.parentFile ?: error("Key envelope has no parent directory"))
        } catch (error: Exception) {
            output?.let { stream -> runCatching { file.failWrite(stream) } }
            throw DatabaseKeyUnavailableException(failureMessage, error)
        }
    }

    private fun syncEnvelopeDirectory(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    fun discardStagedRotation() = synchronized(PROCESS_LOCK) { stagedEnvelopeFile.delete() }

    /** Stores the incoming portable snapshot key wrapped by the existing local Keystore key. */
    fun stagePortableRestoreKey(databaseKey: ByteArray) = synchronized(PROCESS_LOCK) {
        require(databaseKey.size == DATABASE_KEY_BYTES)
        check(hasAtomicFileContents(envelopeFile)) { "Cannot restore over a database without its current key envelope" }
        val sqlCipherPassphrase = PortableSqlCipherSnapshot.roomPassphraseBytes(databaseKey)
        val encrypted = try {
            encrypt(getExistingWrappingKey(), sqlCipherPassphrase)
        } finally {
            sqlCipherPassphrase.fill(0)
        }
        try {
            writeEnvelope(portableRestoreEnvelopeFile, encrypted, "Could not persist portable restore key")
        } finally {
            encrypted.fill(0)
        }
    }

    fun loadPortableRestoreKey(): ByteArray? = synchronized(PROCESS_LOCK) {
        if (!hasAtomicFileContents(portableRestoreEnvelopeFile)) return@synchronized null
        unwrapEnvelope(portableRestoreEnvelopeFile).also {
            if (it.size != PORTABLE_PASSPHRASE_BYTES) {
                it.fill(0)
                throw DatabaseKeyUnavailableException("Portable restore key has invalid length")
            }
        }
    }

    /** Retains the old wrapped key so the old encrypted DB can be restored after any failed cutover. */
    fun preserveEnvelopeForPortableRollback() = synchronized(PROCESS_LOCK) {
        check(hasAtomicFileContents(envelopeFile)) { "Current database key envelope is unavailable" }
        val bytes = try {
            envelopeFile.openRead().use { it.readBytes() }
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException("Could not preserve current database key envelope", error)
        }
        try {
            writeEnvelope(portableRestoreRollbackEnvelopeFile, bytes, "Could not persist rollback key envelope")
        } finally {
            bytes.fill(0)
        }
    }

    /** Promotes the candidate key but keeps both staged and rollback envelopes until startup verifies the DB. */
    fun promotePortableRestoreKey() = synchronized(PROCESS_LOCK) {
        val candidate = loadPortableRestoreKey() ?: throw DatabaseKeyUnavailableException("No portable restore key")
        candidate.fill(0)
        val bytes = try {
            portableRestoreEnvelopeFile.openRead().use { it.readBytes() }
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException("Could not read staged portable restore envelope", error)
        }
        try {
            writeEnvelope(envelopeFile, bytes, "Could not promote portable restore key")
        } finally {
            bytes.fill(0)
        }
    }

    fun rollbackPortableRestoreEnvelope() = synchronized(PROCESS_LOCK) {
        check(hasAtomicFileContents(portableRestoreRollbackEnvelopeFile)) { "Portable rollback key envelope is missing" }
        val bytes = try {
            portableRestoreRollbackEnvelopeFile.openRead().use { it.readBytes() }
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException("Could not read rollback database key envelope", error)
        }
        try {
            writeEnvelope(envelopeFile, bytes, "Could not restore previous database key envelope")
        } finally {
            bytes.fill(0)
        }
    }

    fun clearPortableRestoreEnvelopes() = synchronized(PROCESS_LOCK) {
        portableRestoreEnvelopeFile.delete()
        portableRestoreRollbackEnvelopeFile.delete()
        check(!hasAtomicFileContents(portableRestoreEnvelopeFile) &&
            !hasAtomicFileContents(portableRestoreRollbackEnvelopeFile)) {
            "Portable restore key envelopes could not be removed"
        }
    }

    fun hasPortableRestoreEnvelopes(): Boolean = synchronized(PROCESS_LOCK) {
        hasAtomicFileContents(portableRestoreEnvelopeFile) || hasAtomicFileContents(portableRestoreRollbackEnvelopeFile)
    }

    fun hasDatabaseFile(): Boolean = databaseFile.exists()

    private fun hasAtomicFileContents(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun unwrapEnvelope(file: AtomicFile): ByteArray {
        val wrappingKey = getExistingWrappingKey()
        val bytes = try {
            file.openRead().use { it.readBytes() }
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException("Could not read protected database key envelope", error)
        }
        if (bytes.size < ENVELOPE_HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES || bytes[0] != ENVELOPE_VERSION) {
            throw DatabaseKeyUnavailableException("Unsupported or truncated protected database key envelope")
        }
        val iv = bytes.copyOfRange(1, 1 + GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(1 + GCM_IV_BYTES, bytes.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(ENVELOPE_AAD)
                doFinal(ciphertext)
            }.also {
                if (it.size != DATABASE_KEY_BYTES && it.size != PORTABLE_PASSPHRASE_BYTES) {
                    it.fill(0)
                    throw DatabaseKeyUnavailableException("Unwrapped database key has invalid length")
                }
            }
        } catch (error: DatabaseKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw DatabaseKeyUnavailableException("Could not unwrap protected database key", error)
        } finally {
            bytes.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun getExistingWrappingKey(): SecretKey {
        if (!keyStore.containsAlias(wrappingKeyAlias)) {
            throw DatabaseKeyUnavailableException("Android Keystore wrapping key is missing")
        }
        return (keyStore.getKey(wrappingKeyAlias, null) as? SecretKey)
            ?: throw DatabaseKeyUnavailableException("Android Keystore wrapping key is unavailable")
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        if (keyStore.containsAlias(wrappingKeyAlias)) return getExistingWrappingKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                wrappingKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(WRAPPING_KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(ENVELOPE_AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(ENVELOPE_VERSION)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
            .also { ciphertext.fill(0) }
    }

    internal companion object {
        const val DATABASE_NAME = "memora-agent.db"
        const val KEY_ENVELOPE_NAME = "memora-agent-db-key.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ai.memora.app.agent.database-key-wrap.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DATABASE_KEY_BYTES = 32
        private const val PORTABLE_PASSPHRASE_BYTES = DATABASE_KEY_BYTES * 2
        private const val WRAPPING_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val ENVELOPE_HEADER_BYTES = 1
        private const val ENVELOPE_VERSION: Byte = 1
        private val ENVELOPE_AAD = "ai.memora.app|sqlcipher-key|v1".toByteArray(Charsets.UTF_8)
        private val PROCESS_LOCK = Any()
    }
}

internal class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

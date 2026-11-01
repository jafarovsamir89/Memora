package ai.memora.app.data.protectedstore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
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
    private val databaseFile: File = context.getDatabasePath(DATABASE_NAME),
    private val envelopeFile: AtomicFile = AtomicFile(File(context.noBackupFilesDir, KEY_ENVELOPE_NAME)),
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
    private val wrappingKeyAlias: String = KEY_ALIAS,
) {
    fun loadOrCreate(): ByteArray = synchronized(PROCESS_LOCK) {
        if (envelopeFile.baseFile.exists()) return@synchronized unwrapExistingEnvelope()
        if (databaseFile.exists()) {
            throw DatabaseKeyUnavailableException("Encrypted database exists without its key envelope")
        }

        val wrappingKey = getOrCreateWrappingKey()
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(java.security.SecureRandom()::nextBytes)
        val encrypted = encrypt(wrappingKey, databaseKey)
        val output = envelopeFile.startWrite()
        try {
            output.write(encrypted)
            output.fd.sync()
            envelopeFile.finishWrite(output)
        } catch (error: Exception) {
            envelopeFile.failWrite(output)
            databaseKey.fill(0)
            throw DatabaseKeyUnavailableException("Could not persist protected database key", error)
        } finally {
            encrypted.fill(0)
        }
        databaseKey
    }

    private fun unwrapExistingEnvelope(): ByteArray {
        val wrappingKey = getExistingWrappingKey()
        val bytes = try {
            envelopeFile.openRead().use { it.readBytes() }
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
                if (it.size != DATABASE_KEY_BYTES) {
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

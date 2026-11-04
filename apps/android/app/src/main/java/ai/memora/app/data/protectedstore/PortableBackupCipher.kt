package ai.memora.app.data.protectedstore

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, versioned envelope for a future user-controlled portable backup container. */
internal class PortableBackupCipher(
    private val random: SecureRandom = SecureRandom(),
) {
    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Backup payload exceeds the supported size" }
        validatePassphrase(passphrase)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .put(FORMAT_VERSION)
            .putInt(PBKDF2_ITERATIONS)
            .put(salt)
            .put(iv)
            .array()
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plaintext)
            ByteArrayOutputStream(header.size + encrypted.size).apply {
                write(header)
                write(encrypted)
            }.toByteArray().also { encrypted.fill(0) }
        } finally {
            key.fill(0)
            salt.fill(0)
            iv.fill(0)
        }
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): ByteArray {
        require(container.size in (HEADER_BYTES + GCM_TAG_BYTES)..(HEADER_BYTES + MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES)) {
            "Unsupported or truncated backup container"
        }
        validatePassphrase(passphrase)
        val input = ByteBuffer.wrap(container)
        val magic = ByteArray(MAGIC.size).also(input::get)
        if (!magic.contentEquals(MAGIC)) throw PortableBackupFormatException("Unrecognized backup format")
        val version = input.get()
        if (version != FORMAT_VERSION) throw PortableBackupFormatException("Unsupported backup version")
        val iterations = input.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw PortableBackupFormatException("Unsafe backup key-derivation parameters")
        }
        val salt = ByteArray(SALT_BYTES).also(input::get)
        val iv = ByteArray(IV_BYTES).also(input::get)
        val header = container.copyOfRange(0, HEADER_BYTES)
        val ciphertext = container.copyOfRange(HEADER_BYTES, container.size)
        val key = deriveKey(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            try {
                cipher.doFinal(ciphertext)
            } catch (error: AEADBadTagException) {
                throw PortableBackupAuthenticationException("Backup passphrase is incorrect or file was changed", error)
            }
        } finally {
            key.fill(0)
            salt.fill(0)
            iv.fill(0)
            header.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun validatePassphrase(passphrase: CharArray) {
        require(passphrase.size in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            "Passphrase must contain 12–1024 characters"
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val PBKDF2_ITERATIONS = 600_000
        private const val MIN_ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 2_000_000
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val MIN_PASSPHRASE_CHARS = 12
        private const val MAX_PASSPHRASE_CHARS = 1_024
        private const val MAX_PLAINTEXT_BYTES = 512 * 1024 * 1024
        private val MAGIC = byteArrayOf(0x4d, 0x45, 0x4d, 0x4f, 0x52, 0x41, 0x42, 0x4b) // MEMORABK
        private const val HEADER_BYTES = 8 + 1 + 4 + SALT_BYTES + IV_BYTES
    }
}

internal class PortableBackupFormatException(message: String) : IllegalArgumentException(message)
internal class PortableBackupAuthenticationException(message: String, cause: Throwable) : SecurityException(message, cause)

package ai.memora.app.data.protectedstore

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
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

    /** Encrypts a known-length payload incrementally; callers must discard partial output after failure. */
    fun encrypt(input: InputStream, plaintextBytes: Long, passphrase: CharArray, output: OutputStream): Long {
        require(plaintextBytes in 0..MAX_PLAINTEXT_BYTES.toLong()) { "Backup payload exceeds the supported size" }
        validatePassphrase(passphrase)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(FORMAT_VERSION).putInt(PBKDF2_ITERATIONS).put(salt).put(iv).array()
        val key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var written = header.size.toLong()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            output.write(header)
            var remaining = plaintextBytes
            while (remaining > 0) {
                val request = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, request)
                if (read < 0) throw PortableBackupFormatException("Backup payload ended before its declared length")
                if (read == 0) continue
                cipher.update(buffer, 0, read)?.let { encrypted ->
                    output.write(encrypted)
                    written += encrypted.size
                    encrypted.fill(0)
                }
                remaining -= read
            }
            if (input.read() != -1) throw PortableBackupFormatException("Backup payload exceeds its declared length")
            val finalBytes = cipher.doFinal()
            try {
                output.write(finalBytes)
                written += finalBytes.size
            } finally {
                finalBytes.fill(0)
            }
            output.flush()
            return written
        } finally {
            key.fill(0)
            salt.fill(0)
            iv.fill(0)
            header.fill(0)
            buffer.fill(0)
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

    /** Decrypts incrementally. Do not consume output until this returns: GCM tag verification is final. */
    fun decrypt(input: InputStream, containerBytes: Long, passphrase: CharArray, output: OutputStream): Long {
        require(containerBytes in (HEADER_BYTES + GCM_TAG_BYTES).toLong()..
            (HEADER_BYTES + MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES).toLong()) {
            "Unsupported or truncated backup container"
        }
        validatePassphrase(passphrase)
        val header = ByteArray(HEADER_BYTES)
        readFully(input, header)
        val parsed = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(parsed::get)
        if (!magic.contentEquals(MAGIC)) throw PortableBackupFormatException("Unrecognized backup format")
        if (parsed.get() != FORMAT_VERSION) throw PortableBackupFormatException("Unsupported backup version")
        val iterations = parsed.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw PortableBackupFormatException("Unsafe backup key-derivation parameters")
        }
        val salt = ByteArray(SALT_BYTES).also(parsed::get)
        val iv = ByteArray(IV_BYTES).also(parsed::get)
        val key = deriveKey(passphrase, salt, iterations)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var written = 0L
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            var remaining = containerBytes - HEADER_BYTES
            while (remaining > 0) {
                val request = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, request)
                if (read < 0) throw PortableBackupFormatException("Backup container ended before its declared length")
                if (read == 0) continue
                cipher.update(buffer, 0, read)?.let { plaintext ->
                    output.write(plaintext)
                    written += plaintext.size
                    plaintext.fill(0)
                }
                remaining -= read
            }
            if (input.read() != -1) throw PortableBackupFormatException("Backup container exceeds its declared length")
            try {
                val finalBytes = cipher.doFinal()
                try {
                    output.write(finalBytes)
                    written += finalBytes.size
                } finally {
                    finalBytes.fill(0)
                }
            } catch (error: AEADBadTagException) {
                throw PortableBackupAuthenticationException("Backup passphrase is incorrect or file was changed", error)
            }
            output.flush()
            return written
        } finally {
            key.fill(0)
            salt.fill(0)
            iv.fill(0)
            header.fill(0)
            magic.fill(0)
            buffer.fill(0)
        }
    }

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = input.read(destination, offset, destination.size - offset)
            if (count < 0) throw PortableBackupFormatException("Unsupported or truncated backup container")
            if (count > 0) offset += count
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
        internal const val MAX_CONTAINER_BYTES = 512L * 1024 * 1024 + 57L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private val MAGIC = byteArrayOf(0x4d, 0x45, 0x4d, 0x4f, 0x52, 0x41, 0x42, 0x4b) // MEMORABK
        private const val HEADER_BYTES = 8 + 1 + 4 + SALT_BYTES + IV_BYTES
    }
}

internal class PortableBackupFormatException(message: String) : IllegalArgumentException(message)
internal class PortableBackupAuthenticationException(message: String, cause: Throwable) : SecurityException(message, cause)

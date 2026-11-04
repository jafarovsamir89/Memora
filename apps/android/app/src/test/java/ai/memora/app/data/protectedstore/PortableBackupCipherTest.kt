package ai.memora.app.data.protectedstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupCipherTest {
    private val cipher = PortableBackupCipher()
    private val password = "correct horse battery staple".toCharArray()

    @Test fun roundTripAuthenticatesHeaderAndPayloadWithoutPlaintextLeak() {
        val payload = "synthetic protected database payload".toByteArray()
        val container = cipher.encrypt(payload, password)

        assertFalse(container.toString(Charsets.ISO_8859_1).contains(payload.toString(Charsets.ISO_8859_1)))
        assertArrayEquals(payload, cipher.decrypt(container, password))
    }

    @Test fun wrongPassphraseAndTamperedHeaderOrCiphertextFailClosed() {
        val container = cipher.encrypt("synthetic".toByteArray(), password)
        assertThrows(PortableBackupAuthenticationException::class.java) {
            cipher.decrypt(container, "different passphrase".toCharArray())
        }
        val changedHeader = container.copyOf().also { it[9] = (it[9].toInt() xor 1).toByte() }
        assertThrows(PortableBackupFormatException::class.java) {
            cipher.decrypt(changedHeader, password)
        }
        val changedCiphertext = container.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(PortableBackupAuthenticationException::class.java) {
            cipher.decrypt(changedCiphertext, password)
        }
    }

    @Test fun malformedOrUnboundedContainerParametersAreRejectedBeforeDerivation() {
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(byteArrayOf(1), password) }
        val valid = cipher.encrypt(byteArrayOf(1), password)
        val tooManyIterations = valid.copyOf().also {
            it[9] = 0x7f
            it[10] = 0x7f
            it[11] = 0x7f
            it[12] = 0x7f
        }
        assertThrows(PortableBackupFormatException::class.java) { cipher.decrypt(tooManyIterations, password) }
        assertThrows(IllegalArgumentException::class.java) { cipher.encrypt(byteArrayOf(), "short".toCharArray()) }
    }
}

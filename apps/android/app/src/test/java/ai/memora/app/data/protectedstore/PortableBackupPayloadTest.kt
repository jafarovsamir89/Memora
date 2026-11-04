package ai.memora.app.data.protectedstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PortableBackupPayloadTest {
    @Test fun roundTripPreservesManifestAndSeparateSnapshotKey() {
        val key = ByteArray(32) { it.toByte() }
        val snapshot = "synthetic encrypted SQLCipher bytes".toByteArray()
        val encoded = PortableBackupPayload.encode(18, 57, 1_799_999_999_000L, key, snapshot)

        val decoded = PortableBackupPayload.decode(encoded)

        assertEquals(18, decoded.manifest.roomSchemaVersion)
        assertEquals(57, decoded.manifest.appVersionCode)
        assertEquals(1_799_999_999_000L, decoded.manifest.createdAtEpochMillis)
        assertEquals(snapshot.size, decoded.manifest.encryptedSnapshotBytes)
        assertArrayEquals(key, decoded.snapshotKeyMaterial)
        assertArrayEquals(snapshot, decoded.encryptedSnapshot)
    }

    @Test fun unsupportedSchemaInvalidManifestAndCorruptSnapshotAreRejected() {
        val key = ByteArray(32) { 7 }
        val snapshot = byteArrayOf(2, 4, 6, 8)
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload.encode(17, 57, 1_799_999_999_000L, key, snapshot)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackupPayload.encode(18, 0, 1_799_999_999_000L, key, snapshot)
        }

        val encoded = PortableBackupPayload.encode(18, 57, 1_799_999_999_000L, key, snapshot)
        val changedSchema = encoded.copyOf().also { it[5] = 17 }
        assertThrows(PortableBackupFormatException::class.java) { PortableBackupPayload.decode(changedSchema) }
        val changedSnapshot = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(PortableBackupAuthenticationException::class.java) {
            PortableBackupPayload.decode(changedSnapshot)
        }
        assertThrows(IllegalArgumentException::class.java) { PortableBackupPayload.decode(encoded.copyOf(encoded.size - 1)) }
    }

    @Test fun streamingManifestExtractionVerifiesDigestAndCleansDamagedSnapshot() {
        val key = ByteArray(32) { (it + 9).toByte() }
        val snapshot = ByteArray(150_000) { (it % 253).toByte() }
        val payload = PortableBackupPayload.encode(18, 57, 1_799_999_999_000L, key, snapshot)
        val input = File.createTempFile("memora-payload-test", ".bin")
        val extracted = File.createTempFile("memora-snapshot-test", ".db").also { it.delete() }
        try {
            input.writeBytes(payload)
            val candidate = PortableBackupPayload.extractSnapshotFile(input, extracted)
            candidate.use {
                assertEquals(18, it.manifest.roomSchemaVersion)
                assertArrayEquals(key, it.snapshotKeyMaterial)
                assertArrayEquals(snapshot, it.encryptedSnapshotFile.readBytes())
            }
            assertFalse(extracted.exists())

            val corrupt = payload.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            input.writeBytes(corrupt)
            assertThrows(PortableBackupAuthenticationException::class.java) {
                PortableBackupPayload.extractSnapshotFile(input, extracted)
            }
            assertFalse(extracted.exists())
        } finally {
            input.delete()
            extracted.delete()
            payload.fill(0)
            snapshot.fill(0)
            key.fill(0)
        }
    }
}

package ai.memora.app.data.protectedstore

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}

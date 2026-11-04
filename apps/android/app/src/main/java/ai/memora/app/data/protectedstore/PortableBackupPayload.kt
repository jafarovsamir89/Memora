package ai.memora.app.data.protectedstore

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Authenticated-inner manifest and SQLCipher snapshot payload, itself wrapped by [PortableBackupCipher]. */
internal object PortableBackupPayload {
    private val MAGIC = byteArrayOf(0x4d, 0x53, 0x4e, 0x50) // MSNP
    private const val FORMAT_VERSION: Byte = 1
    private const val DIGEST_BYTES = 32
    private const val SNAPSHOT_KEY_BYTES = 32
    private const val HEADER_BYTES = 4 + 1 + 4 + 4 + 8 + 4 + DIGEST_BYTES + SNAPSHOT_KEY_BYTES
    private const val MAX_SNAPSHOT_BYTES = 512 * 1024 * 1024 - HEADER_BYTES

    fun encode(
        roomSchemaVersion: Int,
        appVersionCode: Int,
        createdAtEpochMillis: Long,
        snapshotKeyMaterial: ByteArray,
        encryptedSnapshot: ByteArray,
    ): ByteArray {
        require(roomSchemaVersion == SUPPORTED_ROOM_SCHEMA) { "Unsupported Room schema version" }
        require(appVersionCode > 0) { "Invalid app version" }
        require(createdAtEpochMillis > 0) { "Invalid backup creation time" }
        require(snapshotKeyMaterial.size == SNAPSHOT_KEY_BYTES) { "Snapshot key must be 256 bits" }
        require(encryptedSnapshot.isNotEmpty() && encryptedSnapshot.size <= MAX_SNAPSHOT_BYTES) {
            "Unsupported SQLCipher snapshot size"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(encryptedSnapshot)
        return try {
            ByteBuffer.allocate(HEADER_BYTES + encryptedSnapshot.size)
                .put(MAGIC)
                .put(FORMAT_VERSION)
                .putInt(roomSchemaVersion)
                .putInt(appVersionCode)
                .putLong(createdAtEpochMillis)
                .putInt(encryptedSnapshot.size)
                .put(digest)
                .put(snapshotKeyMaterial)
                .put(encryptedSnapshot)
                .array()
        } finally {
            digest.fill(0)
        }
    }

    fun decode(payload: ByteArray): DecodedPortableBackup {
        require(payload.size in (HEADER_BYTES + 1)..512 * 1024 * 1024) { "Unsupported backup payload size" }
        val input = ByteBuffer.wrap(payload)
        val magic = ByteArray(MAGIC.size).also(input::get)
        if (!magic.contentEquals(MAGIC)) throw PortableBackupFormatException("Unrecognized backup payload")
        if (input.get() != FORMAT_VERSION) throw PortableBackupFormatException("Unsupported backup payload version")
        val schemaVersion = input.int
        if (schemaVersion != SUPPORTED_ROOM_SCHEMA) {
            throw PortableBackupFormatException("Unsupported Room schema version")
        }
        val appVersionCode = input.int
        val createdAtEpochMillis = input.long
        val snapshotLength = input.int
        if (appVersionCode <= 0 || createdAtEpochMillis <= 0 || snapshotLength !in 1..MAX_SNAPSHOT_BYTES ||
            input.remaining() != DIGEST_BYTES + SNAPSHOT_KEY_BYTES + snapshotLength) {
            throw PortableBackupFormatException("Invalid backup manifest or payload length")
        }
        val expectedDigest = ByteArray(DIGEST_BYTES).also(input::get)
        val snapshotKey = ByteArray(SNAPSHOT_KEY_BYTES).also(input::get)
        val snapshot = ByteArray(snapshotLength).also(input::get)
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(snapshot)
        val digestMatches = MessageDigest.isEqual(expectedDigest, actualDigest)
        expectedDigest.fill(0)
        actualDigest.fill(0)
        if (!digestMatches) {
            snapshotKey.fill(0)
            snapshot.fill(0)
            throw PortableBackupAuthenticationException("Backup snapshot digest does not match", SecurityException("Digest mismatch"))
        }
        return DecodedPortableBackup(
            manifest = PortableBackupManifest(schemaVersion, appVersionCode, createdAtEpochMillis, snapshotLength),
            snapshotKeyMaterial = snapshotKey,
            encryptedSnapshot = snapshot,
        )
    }

    const val SUPPORTED_ROOM_SCHEMA = 18
}

internal data class PortableBackupManifest(
    val roomSchemaVersion: Int,
    val appVersionCode: Int,
    val createdAtEpochMillis: Long,
    val encryptedSnapshotBytes: Int,
)

internal data class DecodedPortableBackup(
    val manifest: PortableBackupManifest,
    val snapshotKeyMaterial: ByteArray,
    val encryptedSnapshot: ByteArray,
)

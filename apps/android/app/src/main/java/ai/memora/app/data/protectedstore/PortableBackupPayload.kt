package ai.memora.app.data.protectedstore

import java.nio.ByteBuffer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    fun headerForSnapshotFile(
        roomSchemaVersion: Int,
        appVersionCode: Int,
        createdAtEpochMillis: Long,
        snapshotKeyMaterial: ByteArray,
        encryptedSnapshotFile: File,
    ): PreparedPortableBackupPayload {
        require(roomSchemaVersion == SUPPORTED_ROOM_SCHEMA) { "Unsupported Room schema version" }
        require(appVersionCode > 0) { "Invalid app version" }
        require(createdAtEpochMillis > 0) { "Invalid backup creation time" }
        require(snapshotKeyMaterial.size == SNAPSHOT_KEY_BYTES) { "Snapshot key must be 256 bits" }
        val byteCount = encryptedSnapshotFile.length()
        require(encryptedSnapshotFile.isFile && byteCount in 1L..MAX_SNAPSHOT_BYTES.toLong()) {
            "Unsupported SQLCipher snapshot size"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var readBytes = 0L
        try {
            FileInputStream(encryptedSnapshotFile).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    readBytes += read
                }
            }
            check(readBytes == byteCount && encryptedSnapshotFile.length() == byteCount) {
                "SQLCipher snapshot changed while preparing its manifest"
            }
            val hash = digest.digest()
            return try {
                val prefix = ByteBuffer.allocate(HEADER_BYTES)
                    .put(MAGIC)
                    .put(FORMAT_VERSION)
                    .putInt(roomSchemaVersion)
                    .putInt(appVersionCode)
                    .putLong(createdAtEpochMillis)
                    .putInt(byteCount.toInt())
                    .put(hash)
                    .put(snapshotKeyMaterial)
                    .array()
                PreparedPortableBackupPayload(
                    manifest = PortableBackupManifest(roomSchemaVersion, appVersionCode,
                        createdAtEpochMillis, byteCount.toInt()),
                    prefix = prefix,
                    payloadBytes = HEADER_BYTES.toLong() + byteCount,
                )
            } finally {
                hash.fill(0)
            }
        } finally {
            buffer.fill(0)
        }
    }

    /** Authenticates the inner manifest and extracts the SQLCipher file without materializing it in RAM. */
    fun extractSnapshotFile(payloadFile: File, snapshotFile: File): PortableBackupFileCandidate {
        require(payloadFile.isFile && payloadFile.length() in (HEADER_BYTES + 1).toLong()..512L * 1024 * 1024) {
            "Unsupported backup payload size"
        }
        require(!snapshotFile.exists()) { "Snapshot staging file already exists" }
        val header = ByteArray(HEADER_BYTES)
        var snapshotKey: ByteArray? = null
        var output: FileOutputStream? = null
        var success = false
        try {
            FileInputStream(payloadFile).use { input ->
                readFully(input, header)
                val parsed = ByteBuffer.wrap(header)
                val magic = ByteArray(MAGIC.size).also(parsed::get)
                if (!magic.contentEquals(MAGIC)) throw PortableBackupFormatException("Unrecognized backup payload")
                if (parsed.get() != FORMAT_VERSION) throw PortableBackupFormatException("Unsupported backup payload version")
                val schemaVersion = parsed.int
                if (schemaVersion != SUPPORTED_ROOM_SCHEMA) {
                    throw PortableBackupFormatException("Unsupported Room schema version")
                }
                val appVersionCode = parsed.int
                val createdAtEpochMillis = parsed.long
                val snapshotLength = parsed.int
                if (appVersionCode <= 0 || createdAtEpochMillis <= 0 || snapshotLength !in 1..MAX_SNAPSHOT_BYTES ||
                    payloadFile.length() != HEADER_BYTES.toLong() + snapshotLength) {
                    throw PortableBackupFormatException("Invalid backup manifest or payload length")
                }
                val expectedDigest = ByteArray(DIGEST_BYTES).also(parsed::get)
                snapshotKey = ByteArray(SNAPSHOT_KEY_BYTES).also(parsed::get)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var remaining = snapshotLength
                output = FileOutputStream(snapshotFile)
                try {
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) throw PortableBackupFormatException("Backup snapshot ended before its declared length")
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    if (input.read() != -1) throw PortableBackupFormatException("Backup payload exceeds its declared length")
                    output.fd.sync()
                    val actualDigest = digest.digest()
                    val matches = MessageDigest.isEqual(expectedDigest, actualDigest)
                    expectedDigest.fill(0)
                    actualDigest.fill(0)
                    if (!matches) {
                        throw PortableBackupAuthenticationException(
                            "Backup snapshot digest does not match",
                            SecurityException("Digest mismatch"),
                        )
                    }
                } finally {
                    buffer.fill(0)
                }
                output.close()
                output = null
                check(snapshotFile.length() == snapshotLength.toLong()) { "Extracted snapshot length changed" }
                success = true
                return PortableBackupFileCandidate(
                    manifest = PortableBackupManifest(schemaVersion, appVersionCode, createdAtEpochMillis, snapshotLength),
                    snapshotKeyMaterial = snapshotKey,
                    encryptedSnapshotFile = snapshotFile,
                ).also { snapshotKey = null }
            }
        } finally {
            runCatching { output?.close() }
            header.fill(0)
            snapshotKey?.fill(0)
            if (!success) snapshotFile.delete()
        }
    }

    private fun readFully(input: FileInputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val read = input.read(destination, offset, destination.size - offset)
            if (read < 0) throw PortableBackupFormatException("Unsupported or truncated backup payload")
            if (read > 0) offset += read
        }
    }

    const val SUPPORTED_ROOM_SCHEMA = 18
}

internal data class PortableBackupManifest(
    val roomSchemaVersion: Int,
    val appVersionCode: Int,
    val createdAtEpochMillis: Long,
    val encryptedSnapshotBytes: Int,
)

internal data class PreparedPortableBackupPayload(
    val manifest: PortableBackupManifest,
    val prefix: ByteArray,
    val payloadBytes: Long,
)

internal data class DecodedPortableBackup(
    val manifest: PortableBackupManifest,
    val snapshotKeyMaterial: ByteArray,
    val encryptedSnapshot: ByteArray,
)

internal class PortableBackupFileCandidate(
    val manifest: PortableBackupManifest,
    val snapshotKeyMaterial: ByteArray,
    val encryptedSnapshotFile: File,
) : AutoCloseable {
    override fun close() {
        snapshotKeyMaterial.fill(0)
        encryptedSnapshotFile.delete()
    }
}

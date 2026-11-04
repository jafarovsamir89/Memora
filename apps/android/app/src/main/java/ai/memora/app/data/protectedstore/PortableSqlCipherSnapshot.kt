package ai.memora.app.data.protectedstore

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/** Creates and validates an encrypted SQLCipher snapshot in app-private no-backup storage. */
internal object PortableSqlCipherSnapshot {
    private const val ATTACHED_ALIAS = "memora_portable_snapshot"
    private const val EXPECTED_ROOM_SCHEMA = 18

    fun export(source: SupportSQLiteDatabase, context: Context, destination: File, snapshotKeyMaterial: ByteArray) {
        require(snapshotKeyMaterial.size == 32) { "Snapshot key material must be 256 bits" }
        val privateDirectory = context.noBackupFilesDir.canonicalFile
        val target = destination.canonicalFile
        require(target.parentFile == privateDirectory) { "Snapshot must stay in app-private no-backup storage" }
        require(!target.exists()) { "Snapshot destination already exists" }
        check(userVersion(source, "main") == EXPECTED_ROOM_SCHEMA) { "Source database schema is not supported" }
        // Use the same passphrase interpretation as SupportOpenHelperFactory(byteArray), not SQLCipher's x'..' raw-key form.
        val passphrase = snapshotKeyMaterial.joinToString("") { "%02x".format(it) }
        var attached = false
        var completed = false
        try {
            source.execSQL(
                "ATTACH DATABASE ? AS $ATTACHED_ALIAS KEY ?",
                arrayOf(target.path, passphrase),
            )
            attached = true
            source.query("SELECT sqlcipher_export('$ATTACHED_ALIAS')").use { cursor ->
                check(cursor.moveToFirst()) { "SQLCipher did not report snapshot export completion" }
            }
            // Zetetic documents that sqlcipher_export does not copy user_version.
            source.execSQL("PRAGMA $ATTACHED_ALIAS.user_version = $EXPECTED_ROOM_SCHEMA")
            checkIntegrity(source, "main")
            checkIntegrity(source, ATTACHED_ALIAS)
            check(tableRowCounts(source, "main") == tableRowCounts(source, ATTACHED_ALIAS)) {
                "SQLCipher snapshot verification did not preserve all table rows"
            }
            check(userVersion(source, ATTACHED_ALIAS) == EXPECTED_ROOM_SCHEMA) {
                "SQLCipher snapshot schema version is incorrect"
            }
            completed = true
        } finally {
            if (attached) runCatching { source.execSQL("DETACH DATABASE $ATTACHED_ALIAS") }
            if (!completed) target.delete()
        }
        check(target.isFile && target.length() > 0L) { "SQLCipher snapshot was not created" }
    }

    fun roomPassphraseBytes(snapshotKeyMaterial: ByteArray): ByteArray {
        require(snapshotKeyMaterial.size == 32) { "Snapshot key material must be 256 bits" }
        return snapshotKeyMaterial.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8)
    }

    private fun userVersion(database: SupportSQLiteDatabase, schema: String): Int =
        database.query("PRAGMA $schema.user_version").use {
            check(it.moveToFirst()) { "Could not read Room schema version" }
            it.getInt(0)
        }

    private fun checkIntegrity(database: SupportSQLiteDatabase, schema: String) {
        database.query("PRAGMA $schema.integrity_check").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                "SQLCipher integrity check failed"
            }
        }
    }

    private fun tableRowCounts(database: SupportSQLiteDatabase, schema: String): Map<String, Long> {
        val names = mutableListOf<String>()
        database.query(
            "SELECT name FROM $schema.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names.associateWith { name ->
            val identifier = name.replace("\"", "\"\"")
            database.query("SELECT COUNT(*) FROM $schema.\"$identifier\"").use {
                check(it.moveToFirst()) { "Could not verify exported table" }
                it.getLong(0)
            }
        }
    }
}

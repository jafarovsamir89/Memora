package ai.memora.app.data.protectedstore

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/** Creates an encrypted SQLCipher snapshot in app-private no-backup storage. */
internal object PortableSqlCipherSnapshot {
    private const val ATTACHED_ALIAS = "memora_portable_snapshot"

    fun export(source: SupportSQLiteDatabase, context: Context, destination: File, snapshotKeyMaterial: ByteArray) {
        require(snapshotKeyMaterial.size == 32) { "Snapshot key material must be 256 bits" }
        val privateDirectory = context.noBackupFilesDir.canonicalFile
        val target = destination.canonicalFile
        require(target.parentFile == privateDirectory) { "Snapshot must stay in app-private no-backup storage" }
        require(!target.exists()) { "Snapshot destination already exists" }
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
            val sourceFacts = source.query("SELECT COUNT(*) FROM profile_fact").use {
                check(it.moveToFirst()); it.getInt(0)
            }
            val snapshotFacts = source.query("SELECT COUNT(*) FROM $ATTACHED_ALIAS.profile_fact").use {
                check(it.moveToFirst()); it.getInt(0)
            }
            check(sourceFacts == snapshotFacts) { "SQLCipher snapshot verification did not preserve profile facts" }
            // Zetetic documents that sqlcipher_export does not copy user_version.
            source.execSQL("PRAGMA $ATTACHED_ALIAS.user_version = 18")
            completed = true
        } finally {
            if (attached) runCatching { source.execSQL("DETACH DATABASE $ATTACHED_ALIAS") }
            if (!completed) target.delete()
        }
        check(target.isFile && target.length() > 0L) { "SQLCipher snapshot was not created" }
    }
}

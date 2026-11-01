package ai.memora.storageprobe

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.security.SecureRandom
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated emulator-only package and synthetic text; never targets the Memora app. */
@RunWith(AndroidJUnit4::class)
class ProbeDatabaseTest {
    @Test fun encryptedStoreRejectsWrongKeyAndRollsBackAtomicTransaction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "synthetic-" + UUID.randomUUID() + ".db"
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val text = "MEMORA_SYNTHETIC_CONFIDENTIAL_FIXTURE"
        try {
            val db = ProbeDatabase.build(context, name, key.copyOf())
            try {
                db.rows().insert(ProbeRow(1, text))
                assertEquals(text, db.rows().get(1)?.syntheticText)
                try {
                    db.runInTransaction {
                        db.rows().insert(ProbeRow(2, "SYNTHETIC_ROLLBACK"))
                        throw IllegalStateException("synthetic rollback")
                    }
                    fail("Transaction should have failed")
                } catch (_: IllegalStateException) { }
                assertNull(db.rows().get(2))
            } finally { db.close() }
            val bytes = context.getDatabasePath(name).readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(text))
            assertFalse(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            val invalid = ProbeDatabase.build(context, name, wrongKey.copyOf())
            val rejected = try { invalid.rows().get(1); false } catch (_: Exception) { true } finally { invalid.close() }
            assertTrue("An incorrect key must not open the database", rejected)
            val reopened = ProbeDatabase.build(context, name, key.copyOf())
            try { assertEquals(text, reopened.rows().get(1)?.syntheticText); assertNull(reopened.rows().get(2)) }
            finally { reopened.close() }
        } finally {
            key.fill(0); wrongKey.fill(0)
            context.deleteDatabase(name) // Only this test's random, synthetic database.
        }
    }
}

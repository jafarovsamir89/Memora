package ai.memora.app.data.protectedstore

import ai.memora.app.UiAgentDatabaseRule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProtectedTableTransferTest {
    @get:Rule val databaseRule = UiAgentDatabaseRule()

    @Test fun v2EditableTransferAtomicallyMergesOverOneHundredRowsAndRetriesIdempotently() = runBlocking {
        val domain = ProtectedDomainRepository(databaseRule.database)
        val scope = "synthetic-table-transfer-scope"
        domain.createTable(scope, "synthetic-transfer-table", "Synthetic transfers", "editable",
            listOf(ColumnSpec("name", "Name", ColumnType.TEXT)))
        val rows = (0 until 125).map { index -> "source-row-$index" to mapOf("name" to "Synthetic row $index") }
        rows.chunked(100).forEach { domain.addRows(scope, "synthetic-transfer-table", 1, it) }

        val exported = domain.exportTable(scope, "synthetic-transfer-table")
        val root = JSONObject(exported)
        assertEquals("memora-table-v2", root.getString("format"))
        assertEquals(125, root.getJSONArray("rows").length())
        val preview = domain.previewImport(scope, "synthetic-transfer-table", exported)
        assertEquals(125, preview.rows.size)
        assertEquals(125, domain.importRows(scope, preview).size)
        assertEquals(125, domain.importRows(scope, preview).size)
        assertEquals(250, domain.tables(scope).single().rows.size)
    }

    @Test fun aConflictInLaterImportBatchRollsBackEarlierRows() = runBlocking {
        val domain = ProtectedDomainRepository(databaseRule.database)
        val scope = "synthetic-table-atomic-transfer-scope"
        domain.createTable(scope, "synthetic-atomic-transfer", "Synthetic atomic", "editable",
            listOf(ColumnSpec("value", "Value", ColumnType.TEXT)))
        (0 until 101).map { index -> "source-$index" to mapOf("value" to "Expected $index") }
            .chunked(100).forEach { domain.addRows(scope, "synthetic-atomic-transfer", 1, it) }
        val preview = domain.previewImport(scope, "synthetic-atomic-transfer", domain.exportTable(scope, "synthetic-atomic-transfer"))
        val conflict = preview.rows[100]
        domain.addRows(scope, "synthetic-atomic-transfer", 1, listOf(conflict.first to mapOf("value" to "Different value")))

        assertTrue(runCatching { domain.importRows(scope, preview) }.isFailure)
        val afterFailure = domain.tables(scope).single().rows
        assertEquals(102, afterFailure.size)
        assertTrue(preview.rows.take(100).none { imported -> afterFailure.any { it.rowId == imported.first } })
    }

    @Test fun v1EditableFilesRemainImportableAndV2RejectsSchemaDrift() = runBlocking {
        val domain = ProtectedDomainRepository(databaseRule.database)
        val scope = "synthetic-table-v1-transfer-scope"
        domain.createTable(scope, "synthetic-v1-transfer", "Synthetic v1", "editable",
            listOf(ColumnSpec("name", "Name", ColumnType.TEXT)))
        domain.addRows(scope, "synthetic-v1-transfer", 1, listOf("source-row" to mapOf("name" to "Synthetic")))
        val v2 = JSONObject(domain.exportTable(scope, "synthetic-v1-transfer"))
        val v1 = JSONObject(v2.toString()).put("format", "memora-table-v1").apply {
            remove("mode")
            remove("viewSpec")
            val columns = getJSONArray("columns")
            for (index in 0 until columns.length()) columns.getJSONObject(index).remove("position")
        }
        val oldPreview = domain.previewImport(scope, "synthetic-v1-transfer", v1.toString())
        assertEquals(1, oldPreview.rows.size)

        val changed = JSONObject(v2.toString())
        changed.getJSONArray("columns").getJSONObject(0).put("name", "Changed")
        assertTrue(runCatching { domain.previewImport(scope, "synthetic-v1-transfer", changed.toString()) }.isFailure)
    }

    @Test fun linkedViewTransferPreservesDefinitionAndNeverCopiesCanonicalSources() = runBlocking {
        val domain = ProtectedDomainRepository(databaseRule.database)
        val scope = "synthetic-linked-transfer-scope"
        domain.createObject(scope, "synthetic-transfer-person", DomainKind.PERSON, "Synthetic person", null, null,
            "2026-11-04T10:00:00Z")
        domain.createTable(scope, "synthetic-linked-transfer", "Synthetic view", "linked_view",
            listOf(ColumnSpec("source_id", "Source ID", ColumnType.TEXT)), viewSpec = LinkedViewSpec(DomainKind.PERSON))

        val exported = domain.exportTable(scope, "synthetic-linked-transfer")
        val root = JSONObject(exported)
        assertEquals(0, root.getJSONArray("rows").length())
        assertEquals("PERSON", root.getJSONObject("viewSpec").getString("sourceKind"))
        val preview = domain.previewImport(scope, "synthetic-linked-transfer", exported)
        assertEquals("linked_view", preview.mode)
        assertTrue(domain.importRows(scope, preview).isEmpty())
        assertTrue(databaseRule.database.protectedStoreDao().rows("synthetic-linked-transfer", scope).isEmpty())
        assertEquals(1, domain.linkedViewPage(scope, "synthetic-linked-transfer", "", true, 0, 25).rows.size)

        val wrongSpec = JSONObject(exported).put("viewSpec", JSONObject().put("version", 1).put("sourceKind", "PROJECT"))
        assertTrue(runCatching { domain.previewImport(scope, "synthetic-linked-transfer", wrongSpec.toString()) }.isFailure)
    }
}

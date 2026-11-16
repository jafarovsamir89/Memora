package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.LinkedViewSpec
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TablesScreenTest {
    @get:Rule(order = 0) val databaseRule = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun createTableModesExposeEditableFieldsAndLinkedSourcesAtLargeFontScale() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                    CompositionLocalProvider(LocalStrings provides context.resources) {
                        MemoraTheme("light") { TablesScreen() }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("tables_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("table_create").performClick()
        compose.onNodeWithTag("table_mode_editable").assertIsDisplayed()
        compose.onNodeWithTag("table_mode_linked_view").assertIsDisplayed()
        compose.onNodeWithTag("table_new_columns").assertIsDisplayed()

        compose.onNodeWithTag("table_mode_linked_view").performClick()
        compose.onNodeWithTag("table_new_columns").assertDoesNotExist()
        compose.onNodeWithTag("table_source_person").assertIsDisplayed()
        compose.onNodeWithTag("table_source_project").assertIsDisplayed()
        compose.onNodeWithTag("table_source_note").assertIsDisplayed()

        compose.onNodeWithTag("table_mode_editable").performClick()
        compose.onNodeWithTag("table_new_columns").assertIsDisplayed()
        compose.onNodeWithTag("table_source_person").assertDoesNotExist()
    }

    @Test fun collectionSearchFindsColumnsAndDetailReturnsToFilteredCollection() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        repository.createTable(scope, "synthetic-invoice-table", "Monthly records", "editable",
            listOf(ColumnSpec("invoice", "Invoice number", ColumnType.TEXT)))
        repository.createTable(scope, "synthetic-travel-table", "Trips", "editable",
            listOf(ColumnSpec("city", "City", ColumnType.TEXT)))
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { TablesScreen() }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_synthetic-invoice-table").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tables_search").performTextInput("invoice")
        compose.onNodeWithTag("table_synthetic-invoice-table").assertIsDisplayed()
        compose.onNodeWithTag("table_synthetic-travel-table").assertDoesNotExist()
        compose.onNodeWithTag("table_synthetic-invoice-table").performClick()
        compose.onNodeWithTag("table_add_row").assertIsDisplayed()
        compose.onNodeWithTag("tables_all").performClick()
        compose.onNodeWithTag("tables_search").assertTextContains("invoice")
        compose.onNodeWithTag("table_synthetic-invoice-table").assertExists()
        compose.onNodeWithTag("table_synthetic-travel-table").assertDoesNotExist()
    }

    @Test fun linkedViewKeepsFilterAndTransferButDoesNotOfferRowOrSchemaWrites() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        repository.createObject(scope, "synthetic-linked-person", DomainKind.PERSON,
            "Synthetic Aysel", "Fictional contact", null, "2026-11-16T09:00:00Z")
        repository.createTable(scope, "synthetic-people-view", "People directory", "linked_view",
            listOf(ColumnSpec("source_id", "Source ID", ColumnType.TEXT)), viewSpec = LinkedViewSpec(DomainKind.PERSON))
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { TablesScreen() }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_synthetic-people-view").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("table_synthetic-people-view").performClick()
        compose.onNodeWithTag("linked_view_filter").assertIsDisplayed()
        compose.onNodeWithTag("linked_view_sort").assertIsDisplayed()
        compose.onNodeWithTag("table_export").assertIsDisplayed()
        compose.onNodeWithTag("table_import").assertIsDisplayed()
        compose.onNodeWithTag("table_add_row").assertDoesNotExist()
        compose.onNodeWithTag("table_add_columns").assertDoesNotExist()
        compose.onNodeWithTag("table_schema_history").assertDoesNotExist()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("linked_view_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("tables_screen_list").performScrollToNode(hasTestTag("linked_view_source_synthetic-linked-person"))
        compose.onNodeWithTag("linked_view_source_synthetic-linked-person").assertIsDisplayed()
    }

    @Test fun selectedTableExposesUserFacingJsonTransferActions() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        ProtectedDomainRepository(databaseRule.database).createTable(scope, "synthetic-transfer-ui-table", "Synthetic table", "editable",
            listOf(ColumnSpec("name", "Name", ColumnType.TEXT)))

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { TablesScreen() }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_synthetic-transfer-ui-table").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("table_synthetic-transfer-ui-table").performScrollTo().performClick()
        compose.onNodeWithTag("table_export").assertIsDisplayed()
        compose.onNodeWithTag("table_import").assertIsDisplayed()
    }

    @Test fun tableMoneyAggregateKeepsCurrenciesSeparateAndUsesExactMinorUnits() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        val domain = ProtectedDomainRepository(databaseRule.database)
        domain.createTable(scope, "synthetic-aggregate-ui-table", "Synthetic totals", "editable",
            listOf(ColumnSpec("amount", "Amount", ColumnType.MONEY)))
        domain.addRows(scope, "synthetic-aggregate-ui-table", 1, listOf(
            "synthetic-azn-one" to mapOf("amount" to mapOf("minorUnits" to 1_001L, "currency" to "AZN")),
            "synthetic-azn-two" to mapOf("amount" to mapOf("minorUnits" to 124L, "currency" to "AZN")),
            "synthetic-usd-one" to mapOf("amount" to mapOf("minorUnits" to 2_500L, "currency" to "USD")),
        ))

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { TablesScreen() }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_synthetic-aggregate-ui-table").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("table_synthetic-aggregate-ui-table").performScrollTo().performClick()
        compose.onNodeWithTag("table_aggregate_run").performScrollTo().performClick()
        compose.onNodeWithTag("tables_screen_list").performScrollToNode(hasTestTag("table_aggregate_USD"))
        compose.onNodeWithTag("table_aggregate_AZN").assertTextEquals("Total: 11.25 AZN")
        compose.onNodeWithTag("table_aggregate_USD").assertTextEquals("Total: 25.00 USD")
    }

    @Test fun ownerCanPreviewAddFieldAndInspectSchemaHistory() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = AgentVoiceTurnProcessor(context).conversationScopeId()
        ProtectedDomainRepository(databaseRule.database).createTable(scope, "synthetic-schema-ui", "Synthetic schema", "editable",
            listOf(ColumnSpec("name", "Name", ColumnType.TEXT)))
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides databaseRule.database) {
                CompositionLocalProvider(LocalStrings provides context.resources) {
                    MemoraTheme("light") { TablesScreen() }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_synthetic-schema-ui").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("table_synthetic-schema-ui").performScrollTo().performClick()
        compose.onNodeWithTag("table_add_columns").performScrollTo().performClick()
        compose.onNodeWithTag("table_add_columns_input").performTextInput("Due:DATE")
        compose.onNodeWithTag("table_add_columns_preview").performClick()
        compose.onNodeWithTag("table_schema_apply").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("table_schema_saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("table_schema_history").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_schema_revision_1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("table_schema_revision_1").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("table_schema_restore_confirm").assertIsDisplayed()
        compose.onNodeWithTag("table_schema_restore_field_name").assertIsDisplayed()

        val snapshot = ProtectedDomainRepository(databaseRule.database).tables(scope).single()
        assertEquals(2L, snapshot.table.schemaVersion)
        assertEquals(listOf("Name", "Due"), snapshot.columns.map { it.name })
    }
}

package ai.memora.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.ColumnSpec
import ai.memora.app.data.protectedstore.ColumnType
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
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("table_aggregate_USD").fetchSemanticsNodes().isNotEmpty()
        }
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
        compose.onNodeWithTag("table_schema_revision_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("table_schema_restore_confirm").assertIsDisplayed()
        compose.onNodeWithTag("table_schema_restore_field_name").assertIsDisplayed()

        val snapshot = ProtectedDomainRepository(databaseRule.database).tables(scope).single()
        assertEquals(2L, snapshot.table.schemaVersion)
        assertEquals(listOf("Name", "Due"), snapshot.columns.map { it.name })
    }
}

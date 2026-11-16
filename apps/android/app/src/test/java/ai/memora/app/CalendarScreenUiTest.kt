package ai.memora.app

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CalendarScreenUiTest {
    @get:Rule(order = 0) val databaseRule = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun filtersCalendarAndOpensExactScopedEventRoute() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = ai.memora.app.voice.AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val zone = ZoneId.of("Asia/Baku")
        val now = LocalDateTime.now(zone).withSecond(0).withNano(0)
        val upcomingId = UUID.randomUUID().toString()
        val pastId = UUID.randomUUID().toString()
        val cancelledId = UUID.randomUUID().toString()
        val foreignId = UUID.randomUUID().toString()
        repository.createCalendarEvent(CalendarInput(upcomingId, scope, "Synthetic future meeting",
            now.plusDays(2).toString(), now.plusDays(2).plusHours(1).toString(), zone.id, "minute"))
        repository.createCalendarEvent(CalendarInput(pastId, scope, "Synthetic past meeting",
            now.minusDays(2).toString(), null, zone.id, "minute"))
        repository.createCalendarEvent(CalendarInput(cancelledId, scope, "Synthetic cancelled meeting",
            now.plusDays(3).toString(), null, zone.id, "minute"))
        repository.createCalendarEvent(CalendarInput(foreignId, "synthetic-foreign-calendar-scope", "Foreign scope meeting",
            now.plusDays(1).toString(), null, zone.id, "minute"))
        repository.cancelCalendarEvent(scope, cancelledId, expectedVersion = 1)

        var opened by mutableStateOf<AppRoute?>(null)
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") { CalendarScreen(onOpenTarget = { opened = it }) }
            }
        }

        compose.waitUntil(10_000) { compose.onAllNodesWithTag("calendar_event_$upcomingId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("calendar_event_$upcomingId").assertIsDisplayed()
        compose.onNodeWithTag("calendar_event_$pastId").assertDoesNotExist()
        compose.onNodeWithTag("calendar_event_$cancelledId").assertDoesNotExist()
        compose.onNodeWithTag("calendar_event_$foreignId").assertDoesNotExist()

        compose.onNodeWithTag("filter_PAST").performClick()
        compose.onNodeWithTag("calendar_event_$pastId").assertExists()
        compose.onNodeWithTag("calendar_event_$upcomingId").assertDoesNotExist()

        compose.onNodeWithTag("filter_CANCELLED").performClick()
        compose.onNodeWithTag("calendar_event_$cancelledId").assertExists()
        compose.onNodeWithTag("calendar_event_status_$cancelledId", useUnmergedTree = true).assertExists()

        compose.onNodeWithTag("filter_ALL").performClick()
        compose.onNodeWithTag("calendar_event_$foreignId").assertDoesNotExist()
        compose.onNodeWithTag("calendar_event_$upcomingId").performClick()
        compose.runOnIdle {
            assertEquals(AppRoute.target(AppTargetKind.CALENDAR, upcomingId), opened)
        }
        compose.runOnIdle { assertEquals(upcomingId, requireNotNull(opened).targetId) }
        Unit
    }

    @Test fun calendarDetailResolvesExactEventOnlyInsideCurrentScope() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = ai.memora.app.voice.AgentVoiceTurnProcessor(context, databaseRule.database).conversationScopeId()
        val repository = ProtectedDomainRepository(databaseRule.database)
        val zone = ZoneId.of("Asia/Baku")
        val ownId = UUID.randomUUID().toString()
        val foreignId = UUID.randomUUID().toString()
        val start = LocalDateTime.now(zone).plusDays(4).withSecond(0).withNano(0).toString()
        repository.createCalendarEvent(CalendarInput(ownId, scope, "Synthetic scoped meeting", start, null, zone.id, "minute"))
        repository.createCalendarEvent(CalendarInput(foreignId, "synthetic-foreign-calendar-scope", "Foreign scoped meeting",
            start, null, zone.id, "minute"))
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") { ActionTargetDetailScreen(requireNotNull(AppRoute.target(AppTargetKind.CALENDAR, ownId))) }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("action_target_$ownId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("action_target_$ownId").assertExists()
        compose.onNodeWithText("Synthetic scoped meeting").assertExists()
        compose.onNodeWithText("Foreign scoped meeting").assertDoesNotExist()
        Unit
    }

    @Test
    @Config(sdk = [33], qualifiers = "w915dp-h412dp-xhdpi")
    fun compactCalendarEmptyStateFitsLandscapeViewport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides context.resources,
                LocalAgentDatabaseOverride provides databaseRule.database,
            ) {
                MemoraTheme("light") {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(64.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) { CalendarScreen() }
                        Box(Modifier.fillMaxWidth().height(80.dp))
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("calendar_empty_title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("calendar_empty_title").assertIsDisplayed()
        compose.onNodeWithTag("calendar_empty_body").assertIsDisplayed()
    }
}

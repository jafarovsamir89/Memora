package ai.memora.app

import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.room.Room
import ai.memora.app.data.protectedstore.AgentActionResultRow
import ai.memora.app.data.protectedstore.AgentConversationMessageRow
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.DomainKind
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.PersonAliasRepository
import ai.memora.app.domain.TaskEvent
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraBottomNavigation
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.ui.MemoraTopBar
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.voice.AgentVoiceTurnProcessor
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID

/** Debug-only screenshot host backed by an in-memory database with fictional records. */
class SyntheticEntityDetailActivity : ComponentActivity() {
    private var syntheticDatabase: AgentDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = Room.inMemoryDatabaseBuilder(this, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syntheticDatabase = database
        val fixture = SyntheticEntityFixture()
        runBlocking { fixture.seed(this@SyntheticEntityDetailActivity, database) }
        val azResources = createConfigurationContext(
            Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag("az-AZ")) },
        ).resources
        val theme = intent.getStringExtra(EXTRA_THEME)?.takeIf { it == "light" || it == "dark" } ?: "light"
        setContent {
            CompositionLocalProvider(
                LocalStrings provides azResources,
                LocalAgentDatabaseOverride provides database,
            ) {
                MemoraTheme(theme) {
                    SyntheticEntityDetailHost(
                        initialRoute = fixture.route(intent.getStringExtra(EXTRA_TARGET_KIND)),
                        onExit = ::finish,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        syntheticDatabase?.close()
        syntheticDatabase = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TARGET_KIND = "targetKind"
        const val EXTRA_THEME = "theme"
    }
}

@androidx.compose.runtime.Composable
private fun SyntheticEntityDetailHost(initialRoute: AppRoute, onExit: () -> Unit) {
    val stateHolder = rememberSaveableStateHolder()
    var navigation by rememberSaveable(stateSaver = AppNavigationState.Saver) {
        mutableStateOf(AppNavigationState.start(initialRoute))
    }
    val route = navigation.current
    val goBack = {
        if (navigation.backStack.isNotEmpty()) navigation = navigation.back() else onExit()
    }
    BackHandler(onBack = goBack)
    val title = s(when (route.targetKind) {
        AppTargetKind.PERSON -> R.string.people
        AppTargetKind.CASE -> R.string.cases
        AppTargetKind.FINANCE -> R.string.finance
        AppTargetKind.TASK -> R.string.tasks_title
        AppTargetKind.SOURCE -> R.string.entity_source_turn
        else -> R.string.action_related_details
    })
    androidx.compose.material3.Scaffold(
        containerColor = colors.background,
        topBar = {
            MemoraTopBar(title = title, showBack = true, onBack = goBack)
        },
        bottomBar = { MemoraBottomNavigation(route.selectedPrimaryTab) { navigation = navigation.selectTab(it) } },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).testTag("synthetic_detail_host")) {
            stateHolder.SaveableStateProvider(route.stateKey) {
                ActionTargetDetailScreen(route) { navigation = navigation.navigate(it) }
            }
        }
    }
}

private class SyntheticEntityFixture {
    val personId = "00000000-0000-4000-8000-000000000301"
    val financePersonId = "00000000-0000-4000-8000-000000000302"
    val financeId = "00000000-0000-4000-8000-000000000303"
    val caseId = "00000000-0000-4000-8000-000000000304"
    val taskId = "00000000-0000-4000-8000-000000000305"
    val evidenceId = "00000000-0000-4000-8000-000000000306"
    val turnId = "00000000-0000-4000-8000-000000000307"

    suspend fun seed(activity: ComponentActivity, database: AgentDatabase) {
        val scope = AgentVoiceTurnProcessor(activity, database).conversationScopeId()
        val domain = ProtectedDomainRepository(database)
        val cases = ProtectedCaseRepository(database)
        val at = "2026-11-16T10:00:00Z"
        domain.createObject(scope, personId, DomainKind.PERSON, "Synthetic Elchin", "Vehicle repair contact", null, at)
        PersonAliasRepository(database).add(scope, personId, "Synthetic E.", nowMillis = 1_000L)
        domain.recordFinanceEvent(scope, "Synthetic Elchin", "receipt", 15_000, "AZN", "Fictional advance",
            "Synthetic repair advance", financePersonId, financeId, UUID.randomUUID().toString(), at, at)
        cases.confirmFinancePersonIdentity(scope, financePersonId, personId, confirmedBy = "owner", now = at)
        cases.createCase(scope, caseId, "Synthetic BMW repair", "Fictional parts are expected Friday.",
            nextAction = "Confirm fictional parts delivery", sourceTurnId = turnId, now = at)
        cases.addParticipant(scope, caseId, personId, role = "owner", now = at)
        cases.addEvidence(scope, caseId, "money_event", financeId, "advance", evidenceId = evidenceId, now = at)
        domain.appendTaskEvent(TaskEvent.Created(taskId, scope, "synthetic-owner", UUID.randomUUID().toString(),
            "Synthetic follow up about parts"))
        cases.confirmTaskPersonLink(scope, taskId, personId, "assignee", confirmedBy = "owner", now = at)
        cases.addEvidence(scope, caseId, "task", taskId, "follow_up", now = at)
        val dao = database.protectedStoreDao()
        dao.putConversationMessages(listOf(
            AgentConversationMessageRow(scopeId = scope, turnId = turnId, role = "user",
                message = "Synthetic request about the BMW repair.", createdAtMillis = 1_000L),
            AgentConversationMessageRow(scopeId = scope, turnId = turnId, role = "model",
                message = "Synthetic repair case and follow-up are ready.", createdAtMillis = 1_001L),
        ))
        dao.putActionResults(listOf(AgentActionResultRow(
            resultId = "00000000-0000-4000-8000-000000000308",
            scopeId = scope,
            turnId = turnId,
            toolName = "create_task",
            status = "APPLIED",
            code = "saved",
            targetKind = "task",
            targetId = taskId,
            createdAtMillis = 1_001L,
        )))
    }

    fun route(kind: String?): AppRoute {
        val targetKind = kind?.let(AppTargetKind::fromKey) ?: AppTargetKind.CASE
        val id = when (targetKind) {
            AppTargetKind.PERSON -> personId
            AppTargetKind.CASE -> caseId
            AppTargetKind.FINANCE -> financeId
            AppTargetKind.TASK -> taskId
            AppTargetKind.SOURCE -> evidenceId
            else -> caseId
        }
        return requireNotNull(AppRoute.target(targetKind, id))
    }
}

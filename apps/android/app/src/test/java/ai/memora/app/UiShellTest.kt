package ai.memora.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.AppearanceStore
import ai.memora.app.data.AppearanceSettings
import ai.memora.app.data.AtomicAppearanceStore
import ai.memora.app.data.CapturePersistence
import ai.memora.app.data.EncryptedCaptureStore
import ai.memora.app.data.LocalCapturePersistence
import ai.memora.app.data.protectedstore.CalendarInput
import ai.memora.app.data.protectedstore.ProtectedCaseRepository
import ai.memora.app.data.protectedstore.ProtectedDomainRepository
import ai.memora.app.data.protectedstore.PortableBackupFileCandidate
import ai.memora.app.data.protectedstore.PortableBackupManifest
import org.junit.Assert
import org.junit.Before

/** Runs Android 13 UI semantics and native rasterization on the JVM, not a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiShellTest {
    @get:Rule(order = 0) val agentDatabase = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private lateinit var captureStore: CapturePersistence
    private lateinit var appearanceStore: AppearanceStore
    private lateinit var taskStore: ai.memora.app.data.EncryptedTaskStore

    @Test fun revokeClearsSavedVoiceConsents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("voice-first-consent", 0)
        prefs.edit().putBoolean("google-note-v1", true).putBoolean("google-live-v1", true)
            .putBoolean("google-audio-and-answer-v1", true)
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, true).commit()
        compose.setContent { TestMemoraApp(initialRoute = "settings", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("voice_revoke_consent").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(prefs.all.isEmpty()) }
        compose.onNodeWithTag("voice_revoke_consent").assertDoesNotExist()
    }

    @Test fun portableBackupRequiresLongMatchingPassphraseBeforeLaunchingDocumentPicker() {
        compose.setContent {
            TestMemoraApp(initialRoute = "portable_backup", capturePersistenceOverride = captureStore,
                appearanceStoreOverride = appearanceStore)
        }
        compose.onNodeWithTag("portable_backup_export").performScrollTo().performClick()
        compose.onNodeWithTag("portable_backup_error").assertIsDisplayed()
        val shortPasswordError = compose.onNodeWithTag("portable_backup_error").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }

        compose.onNodeWithTag("portable_backup_password").performTextInput("memora test pass 12")
        compose.onNodeWithTag("portable_backup_repeat_password").performTextInput("different password")
        compose.onNodeWithTag("portable_backup_export").performScrollTo().performClick()
        compose.onNodeWithTag("portable_backup_error").assertIsDisplayed()
        val mismatchError = compose.onNodeWithTag("portable_backup_error").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }
        Assert.assertNotEquals(shortPasswordError, mismatchError)
    }

    @Test fun portableBackupCandidateReplacementDisposesOnlyThePreviousCandidate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun candidate(): PortableBackupFileCandidate {
            val snapshot = File(context.cacheDir, "portable-candidate-${UUID.randomUUID()}.db")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            return PortableBackupFileCandidate(
                PortableBackupManifest(18, 1, 1, 3),
                ByteArray(32) { 7 },
                snapshot,
            )
        }
        val previous = candidate()
        val replacement = candidate()
        compose.setContent {
            var selected by remember { mutableStateOf<PortableBackupFileCandidate?>(previous) }
            PortableBackupCandidateLifecycle(selected)
            androidx.compose.material3.Button(
                onClick = { selected = replacement },
                modifier = androidx.compose.ui.Modifier.testTag("replace_candidate"),
            ) { androidx.compose.material3.Text("Replace") }
        }

        compose.onNodeWithTag("replace_candidate").performClick()
        compose.runOnIdle {
            Assert.assertFalse(previous.encryptedSnapshotFile.exists())
            Assert.assertTrue(replacement.encryptedSnapshotFile.exists())
        }
    }

    @Test fun sqlcipherCommunityLicenseIsReachableFromAbout() {
        compose.setContent { TestMemoraApp(initialRoute = "home", initialLanguage = "az", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("app_info").performClick()
        compose.onNodeWithTag("open_source_licenses").performClick()
        compose.onNodeWithTag("sqlcipher_license_text").assertExists()
        compose.onNodeWithText("SQLCipher Community Edition — Copyright (c) 2008-2026, ZETETIC, LLC").assertExists()
        compose.onNodeWithText("Redistribution and use in source and binary forms", substring = true).assertExists()
        compose.onNodeWithTag("all_open_source_licenses").assertIsDisplayed().performClick()
    }

    @Before fun setupCaptureStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(File(context.cacheDir, "ui-settings-${UUID.randomUUID()}"), AppearanceSettings("ru", "light"))
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        captureStore = LocalCapturePersistence(EncryptedCaptureStore(File(context.cacheDir, "ui-capture-${UUID.randomUUID()}.bin")) { key })
        taskStore = ai.memora.app.data.EncryptedTaskStore(File(context.cacheDir, "ui-tasks-${UUID.randomUUID()}.bin")) { key }
    }

    private fun awaitCaptureReady() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("capture_input") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitAppearance(language: String, theme: String) {
        // Compose becoming idle does not imply that Dispatchers.IO has saved settings.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("filter_$language") and isSelected() and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("filter_$theme") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(AppearanceSettings(language, theme), appearanceStore.read())
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val output = File(System.getProperty("memora.screenshots"), "$name.png")
        output.parentFile?.mkdirs()
        // captureToImage waits for a real compositor frame, which Robolectric cannot
        // deliver. Draw the real View hierarchy through Robolectric native graphics.
        // https://github.com/robolectric/robolectric/issues/8071
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 && it.height > 0 }
            check(views.isNotEmpty()) { "No visible Android window to render" }
            val bitmap = Bitmap.createBitmap(views.maxOf { it.width }, views.maxOf { it.height }, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            views.forEach { it.draw(canvas) }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun navigationSearchAndSource() {
        val now = java.time.Instant.parse("2026-01-01T12:00:00Z")
        captureStore.saveDraft(0, "İlqar, yaddaş yoxlaması")
        captureStore.submit("cmd", 1, "synthetic-source", "r1", "İlqar, yaddaş yoxlaması", now, "Asia/Baku")
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.onNodeWithTag("voice_first_button").assertIsDisplayed()
        compose.onNodeWithTag("nav_home").assertIsDisplayed()
        compose.onNodeWithTag("nav_chat").assertIsDisplayed()
        compose.onNodeWithTag("nav_cases").assertIsDisplayed()
        compose.onNodeWithTag("nav_memory").assertIsDisplayed()
        compose.onNodeWithTag("nav_more").assertIsDisplayed()
        compose.onNodeWithTag("open_settings").assertIsDisplayed()
        screenshot("01-home-light")
        compose.onNodeWithTag("nav_chat").performClick()
        compose.onNodeWithTag("secretary_voice_card").assertIsDisplayed()
        screenshot("13-secretary-light")
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        compose.onNodeWithTag("nav_more").performClick()
        compose.onNodeWithTag("more_tasks").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("more_voice_memory").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("more_people").performScrollTo().assertExists()
        compose.onNodeWithTag("more_portable_backup").performScrollTo().performClick()
        compose.onNodeWithTag("portable_backup_password").assertIsDisplayed()
        compose.onNodeWithTag("portable_backup_export").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("portable_backup_import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav_cases").performClick()
        compose.onNodeWithTag("nav_cases").assertIsDisplayed()
        compose.onNodeWithTag("nav_memory").performClick()
        screenshot("02-memory-light")
        compose.onNodeWithTag("memory_search").performTextInput("İLQAR")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("memory_synthetic-source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("memory_synthetic-source").performScrollTo().performClick()
        compose.onNodeWithTag("stored_source_text").assertTextContains("İlqar, yaddaş yoxlaması")
        screenshot("03-source-light")
    }

    @Test fun emptyCasesHasSinglePageTitleAndOpensSecretary() {
        appearanceStore = AtomicAppearanceStore(File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "ui-cases-settings-${UUID.randomUUID()}"), AppearanceSettings("az", "light"))
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialTheme = "light", initialLanguage = "az", initialRoute = "cases",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cases_empty_open_secretary").fetchSemanticsNodes().isNotEmpty() }
        // One top-bar title plus the selected navigation label; the old duplicate
        // in the cases content would have made this count three.
        compose.onAllNodesWithText("İşlər").assertCountEquals(2)
        compose.onNodeWithTag("cases_empty_open_secretary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("secretary_voice_card").assertIsDisplayed()
    }

    @Test fun moreCatalogPrecedesReportsAndKeepsAllFivePrimaryTabs() {
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "more", initialTheme = "light", capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = appearanceStore)
            }
        }
        listOf("home", "chat", "cases", "memory", "more").forEach { tab ->
            compose.onNodeWithTag("nav_$tab").assertExists()
        }
        compose.onNodeWithTag("more_tasks").assertExists()
        compose.onNodeWithTag("finance_reports_section").assertExists()
        val semantics = compose.onRoot(useUnmergedTree = true).printToString()
        val directoryIndex = semantics.indexOf("more_tasks")
        val peopleGroupIndex = semantics.indexOf("more_group_people_work")
        val planningGroupIndex = semantics.indexOf("more_group_planning_memory")
        val dataGroupIndex = semantics.indexOf("more_group_data_settings")
        val reportsIndex = semantics.indexOf("finance_reports_section")
        Assert.assertTrue("More directory should appear before finance reports ($directoryIndex, $reportsIndex)",
            directoryIndex >= 0 && reportsIndex > directoryIndex)
        Assert.assertTrue("More categories should stay in task-oriented order",
            peopleGroupIndex >= 0 && peopleGroupIndex < planningGroupIndex && planningGroupIndex < dataGroupIndex && dataGroupIndex < reportsIndex)
        screenshot("18-more-directory-first")
    }

    @Test fun actionTargetOpensExactScopedObjectAndSelectsItsOwningTab() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = agentDatabase.database
        val scope = ai.memora.app.voice.AgentVoiceTurnProcessor(context, database).conversationScopeId()
        val id = UUID.randomUUID().toString()
        ProtectedCaseRepository(database).createCase(scope, id, "Synthetic exact target", "Stable target details",
            nextAction = "Review exact target", sourceTurnId = UUID.randomUUID().toString())
        val route = requireNotNull(AppTargetKind.fromAction("case", id)).encode()

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides database) {
                TestMemoraApp(initialRoute = route, initialTheme = "light", capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = appearanceStore)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("action_target_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_cases").assertIsSelected()
        compose.onNodeWithTag("case_detail_header").assertExists()
        compose.onNodeWithTag("case_detail_summary").assertTextEquals("Stable target details")
        screenshot("19-exact-action-target")
    }

    @Test fun reportsScreenReadsAndLabelsSyntheticLedgerWithoutClaimingProfit() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(File(context.cacheDir, "ui-report-settings-${UUID.randomUUID()}"),
            AppearanceSettings("az", "light"))
        val database = agentDatabase.database
        val processor = ai.memora.app.voice.AgentVoiceTurnProcessor(context, database)
        val scope = processor.conversationScopeId()
        val instant = java.time.Instant.now().toString()
        val repository = ProtectedDomainRepository(database)
        repository.recordFinanceEvent(scope, "Synthetic Report Client", "receipt", 8_125, "AZN",
            "Synthetic UI fixture", null, "synthetic-report-person", "synthetic-report-receipt", "synthetic-report-receipt-command", instant, instant)
        repository.recordFinanceEvent(scope, "Synthetic Report Client", "expense", 2_500, "AZN",
            "Synthetic UI fixture", null, "synthetic-report-person", "synthetic-report-expense", "synthetic-report-expense-command", instant, instant)
        repository.recordFinanceEvent(scope, "Synthetic Report Client", "debt", 35_000, "AZN",
            "Synthetic UI fixture", null, "synthetic-report-person", "synthetic-report-debt", "synthetic-report-debt-command", instant, instant)

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides database) {
                TestMemoraApp(initialTheme = "light", initialLanguage = "az",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.onNodeWithTag("nav_more").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("finance_report_received_AZN").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("finance_reports_section").assertExists()
        compose.onNodeWithTag("finance_report_received_AZN").assertTextContains("81,25 AZN", substring = true)
        compose.onNodeWithTag("finance_report_spent_AZN").assertTextContains("25,00 AZN", substring = true)
        compose.onNodeWithTag("finance_report_debt_originated_AZN").assertTextContains("350,00 AZN", substring = true)
        compose.onNodeWithTag("finance_report_not_profit").assertExists()
        compose.onNodeWithTag("finance_report_open_debt_AZN").assertExists()
        compose.onNodeWithTag("finance_report_counterparties").assertExists()
        compose.onNodeWithTag("finance_report_coverage").assertExists()
        screenshot("16-finance-reports-top")
        compose.onNodeWithTag("more_tasks").performScrollTo().assertExists()
        screenshot("17-finance-reports-tools")
    }

    @Test fun financeListFiltersByLedgerKindAndOpensTheExactOperation() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(File(context.cacheDir, "ui-finance-list-settings-${UUID.randomUUID()}"),
            AppearanceSettings("az", "light"))
        val database = agentDatabase.database
        val scope = ai.memora.app.voice.AgentVoiceTurnProcessor(context, database).conversationScopeId()
        val repository = ProtectedDomainRepository(database)
        val now = java.time.Instant.parse("2026-11-16T12:00:00Z").toString()
        val receiptId = UUID.randomUUID().toString()
        val debtId = UUID.randomUUID().toString()
        repository.recordFinanceEvent(scope, "Synthetic Finance Person", "receipt", 12_550, "AZN",
            "Synthetic finance list source", "Synthetic receipt note", "synthetic-finance-person", receiptId,
            UUID.randomUUID().toString(), now, now)
        repository.recordFinanceEvent(scope, "Synthetic Finance Person", "debt", 30_000, "AZN",
            "Synthetic finance list source", "Synthetic debt note", "synthetic-finance-person", debtId,
            UUID.randomUUID().toString(), now, now)

        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 1.3f),
                LocalAgentDatabaseOverride provides database,
            ) {
                TestMemoraApp(initialRoute = "more", initialTheme = "light", initialLanguage = "az",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.onNodeWithTag("more_finance").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance_event_$receiptId").fetchSemanticsNodes().isNotEmpty() }
        listOf("ALL", "RECEIVED", "EXPENSES", "DEBTS").forEach { filter ->
            compose.onNodeWithTag("filter_$filter").assertIsDisplayed()
        }
        compose.onAllNodesWithText("Maliyyə", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("finance_subtitle").assertTextContains("Gəlirlər", substring = true)
        compose.onNodeWithTag("finance_event_amount_$receiptId", useUnmergedTree = true).assertTextContains("125,50 AZN")
        compose.onNodeWithTag("filter_RECEIVED").performClick()
        compose.onNodeWithTag("finance_event_$receiptId").assertExists()
        Assert.assertTrue(compose.onAllNodesWithTag("finance_event_$debtId").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("finance_event_$receiptId").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("action_target_$receiptId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("finance_detail_amount").assertTextContains("125,50 AZN")
        compose.onNodeWithTag("back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance_event_$receiptId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_event_$receiptId").assertExists()
        compose.onNodeWithTag("filter_RECEIVED").assertIsSelected()
        Unit
    }

    @Test fun todayDashboardUsesSavedTasksCasesAndCalendarEvents() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(
            File(context.cacheDir, "ui-today-settings-${UUID.randomUUID()}"),
            AppearanceSettings("az", "light"),
        )
        val scope = ai.memora.app.voice.AgentVoiceTurnProcessor(context).conversationScopeId()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Baku"))
        val due = today.plusDays(1)
        val taskId = UUID.randomUUID().toString()
        val caseId = UUID.randomUUID().toString()
        val calendarId = UUID.randomUUID().toString()
        val debtId = UUID.randomUUID().toString()
        taskStore.append(ai.memora.app.domain.TaskEvent.Created(taskId, scope, "owner", "synthetic-create",
            "Synthetic priority task"))
        taskStore.append(ai.memora.app.domain.TaskEvent.Updated(taskId, scope, "owner", "synthetic-due", 1,
            "Synthetic priority task", due.atTime(9, 0).toString(), "Asia/Baku", false, null, null))
        val cases = ProtectedCaseRepository(agentDatabase.database)
        val domains = ProtectedDomainRepository(agentDatabase.database)
        cases.createCase(scope, caseId, "Synthetic open case", null, nextAction = "Review synthetic item",
            sourceTurnId = UUID.randomUUID().toString(), now = java.time.Instant.now().toString())
        domains.createCalendarEvent(CalendarInput(calendarId, scope, "Synthetic upcoming event",
            due.toString(), null, "Asia/Baku", "date"))
        domains.recordFinanceEvent(scope, "Synthetic debtor", "debt", 31_500, "AZN", "Synthetic obligation",
            "Fictional unpaid balance", UUID.randomUUID().toString(), debtId, UUID.randomUUID().toString(),
            java.time.Instant.now().toString(), java.time.Instant.now().toString())

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore, taskStoreOverride = taskStore)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("today_task_$taskId").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("today_case_$caseId").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("today_event_$calendarId").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("today_debt_$debtId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("today_conversation_entry").assertExists()
        compose.onNodeWithTag("today_shared_voice_control").assertExists()
        compose.onNodeWithTag("capture_voice").assertDoesNotExist()
        compose.onNodeWithTag("today_task_$taskId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_case_$caseId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_event_$calendarId").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_debt_$debtId").performScrollTo().assertTextContains("315.00 AZN", substring = true)
        Assert.assertEquals(1, cases.openCaseCount(scope))
        screenshot("14-today-with-local-data")

        compose.onNodeWithTag("today_debt_$debtId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("finance_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("finance_detail_direction").assertTextContains(
            "Borc qeydi · gəlir deyil", substring = true)
        compose.onNodeWithTag("back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today_case_$caseId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today_case_$caseId").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("case_detail_header").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("case_action_complete").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            kotlinx.coroutines.runBlocking { agentDatabase.database.protectedStoreDao().caseRecord(scope, caseId)?.status == "completed" }
        }
        compose.onNodeWithTag("back").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today_cases_metric").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today_case_$caseId").assertDoesNotExist()
        compose.onNodeWithTag("today_cases_metric").assertTextContains("0", substring = true)
        Unit
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun todayLandscapeAtLargeFontShowsConversationAndPriorityHeadingAboveNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(
            File(context.cacheDir, "ui-today-landscape-${UUID.randomUUID()}"),
            AppearanceSettings("az", "light"),
        )
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalAgentDatabaseOverride provides agentDatabase.database,
                LocalDensity provides Density(baseDensity.density, 1.3f),
            ) {
                TestMemoraApp(initialRoute = "home", initialLanguage = "az",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore,
                    taskStoreOverride = taskStore)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("today_conversation_entry").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bottomNavigation = compose.onNodeWithTag("nav_home").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("Regression must run at landscape dimensions: $root", root.width > root.height)
        val voiceButton = compose.onNodeWithTag("voice_first_button", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val priorities = compose.onNodeWithText("Bu gün diqqət yetirək").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(
            "Landscape Today must show the entire voice control above bottom navigation; " +
                "voiceBottom=${voiceButton.bottom}, navigationTop=${bottomNavigation.top}",
            voiceButton.bottom <= bottomNavigation.top,
        )
        val minimumTarget = 96f // The xhdpi test configuration renders at 2 px per dp.
        Assert.assertTrue("Voice control touch target must remain at least 48dp: $voiceButton",
            voiceButton.width >= minimumTarget && voiceButton.height >= minimumTarget)
        Assert.assertTrue(
            "Azerbaijani priorities heading at 1.3x must not be hidden by bottom navigation; " +
                "headingBottom=${priorities.bottom}, navigationTop=${bottomNavigation.top}",
            priorities.bottom < bottomNavigation.top,
        )
    }

    @Test fun dailyAttentionIsVisibleButStartsOptedOut() {
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "settings", capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = appearanceStore)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("attention_enabled") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("attention_enabled").assertIsOff()
        compose.onNodeWithTag("attention_settings_error").assertDoesNotExist()
        compose.onNodeWithText("По желанию локальная сводка", substring = true).assertExists()
    }

    @Test fun secretaryShowsPersistedActionStatusSourceAndRelatedArea() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = ai.memora.app.voice.AgentVoiceTurnProcessor(context, agentDatabase.database)
        val scopeId = processor.conversationScopeId()
        val turnId = UUID.randomUUID().toString()
        val targetId = UUID.randomUUID().toString()
        val instant = java.time.Instant.now().toString()
        ai.memora.app.data.protectedstore.ProtectedDomainRepository(agentDatabase.database).recordFinanceEvent(
            scopeId, "Synthetic Receipt Person", "receipt", 4_250, "AZN", "Synthetic action fixture",
            "Synthetic receipt detail", UUID.randomUUID().toString(), targetId, UUID.randomUUID().toString(), instant, instant,
        )
        ai.memora.app.data.protectedstore.ProtectedConversationRepository(agentDatabase.database).appendTurn(
            scopeId, turnId, "Synthetic request about a saved receipt", "Synthetic action confirmed.",
            1_800_000_000_000L,
            listOf(ai.memora.app.data.protectedstore.AgentActionResultRow(
                UUID.randomUUID().toString(), scopeId, turnId, "record_money_event", "APPLIED", "receipt_recorded",
                "finance", targetId, 1_800_000_000_000L,
            ), ai.memora.app.data.protectedstore.AgentActionResultRow(
                UUID.randomUUID().toString(), scopeId, turnId, "delete_profile_fact", "CONFLICT", "profile_fact_not_found",
                "memory", null, 1_800_000_000_001L,
            )),
        )
        val repository = ai.memora.app.data.protectedstore.ProtectedConversationRepository(agentDatabase.database)
        Assert.assertEquals(listOf("Synthetic request about a saved receipt", "Synthetic action confirmed."),
            repository.recent(scopeId, 80).map { it.message })
        Assert.assertEquals(2, repository.actionResults(scopeId, turnId).size)
        Assert.assertEquals(2, repository.actionResultsForTurns(scopeId, listOf(turnId))[turnId]?.size)
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "chat", capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = AtomicAppearanceStore(
                        File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "ui-action-card-${UUID.randomUUID()}"),
                        AppearanceSettings("az", "light"),
                    ))
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("action_result_${turnId}_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_history").performScrollToNode(hasTestTag("chat_turn_${turnId}_model"))
        compose.onNodeWithTag("chat_turn_${turnId}_model").assertIsDisplayed()
        compose.onNodeWithTag("action_result_${turnId}_0").performScrollTo().assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Mənbə: Synthetic action fixture", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("42,50 AZN", substring = true).assertExists()
        compose.onNodeWithText("Synthetic Receipt Person", substring = true).assertExists()
        compose.onNodeWithText("Yadda saxlanıldı").assertIsDisplayed()
        compose.onNodeWithTag("action_result_${turnId}_1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("action_delete_not_confirmed").assertTextEquals(
            "Silinmə təsdiqlənməyib. Yaddaşı yoxlamadan əməliyyatı təkrarlamayın.")
        screenshot("15-secretary-action-card")
        compose.onAllNodesWithTag("action_open_source").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("action_source_user_message").assertExists()
        compose.onNodeWithTag("action_source_assistant_message").assertExists()
        compose.onNodeWithTag("action_source_close").performClick()
        compose.onNodeWithTag("action_prepare_correction").performScrollTo().performClick()
        val correctionDraft = compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        Assert.assertTrue(correctionDraft.contains(targetId))
        compose.onNodeWithTag("action_correction_draft_note").assertExists()
        compose.onAllNodesWithTag("action_open_related").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("nav_more").assertIsSelected()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("action_target_$targetId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("action_target_$targetId").assertExists()
        compose.onNodeWithText("Synthetic receipt detail").assertExists()
        Unit
    }

    @Test fun secretaryAmbiguityCardShowsNoWriteAndOffersNoTargetOrCorrection() = kotlinx.coroutines.runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = ai.memora.app.voice.AgentVoiceTurnProcessor(context, agentDatabase.database)
        val scopeId = processor.conversationScopeId()
        val turnId = UUID.randomUUID().toString()
        val before = agentDatabase.database.protectedStoreDao().moneyEvents(scopeId).size
        ai.memora.app.data.protectedstore.ProtectedConversationRepository(agentDatabase.database).appendTurn(
            scopeId,
            turnId,
            "Sintetik yoxlama: Ruslan mənə 150 manat qaytardı; hansı Ruslan olduğunu dəqiqləşdirin.",
            "Hansı Ruslanı nəzərdə tutursunuz? Qeydi dəqiqləşdirmədən heç nə saxlamadım.",
            1_800_000_000_100L,
            listOf(ai.memora.app.data.protectedstore.AgentActionResultRow(
                UUID.randomUUID().toString(), scopeId, turnId, "record_money_event", "NEEDS_CONFIRMATION",
                "person_name_ambiguous", null, null, 1_800_000_000_100L,
            )),
        )
        val after = agentDatabase.database.protectedStoreDao().moneyEvents(scopeId).size
        Assert.assertEquals(before, after)

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "chat", initialLanguage = "az", capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = AtomicAppearanceStore(
                        File(context.cacheDir, "ui-ambiguity-${UUID.randomUUID()}"), AppearanceSettings("az", "light"),
                    ))
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("action_result_${turnId}_0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("action_result_${turnId}_0").performScrollTo()
        compose.onNodeWithTag("chat_turn_${turnId}_user").assertExists()
        compose.onNodeWithTag("chat_turn_${turnId}_model").assertExists()
        compose.onNodeWithTag("action_status_label").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Təsdiq gözlənilir").assertExists()
        compose.onNodeWithText("Təsdiq verilməyənədək heç bir dəyişiklik edilməyib.").assertExists()
        compose.onNodeWithText("Yadda saxlanıldı").assertDoesNotExist()
        compose.onAllNodesWithTag("action_open_related").assertCountEquals(0)
        compose.onAllNodesWithTag("action_prepare_correction").assertCountEquals(0)
    }

    @Test fun failedTextTurnKeepsTheOriginalDraftAndDoesNotRetryAutomatically() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.TEXT, false).commit()
        var calls = 0
        val request = "Synthetic request that must stay available after failure"
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "chat", initialLanguage = "ru",
                    capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = AtomicAppearanceStore(
                        File(context.cacheDir, "ui-chat-failure-${UUID.randomUUID()}"), AppearanceSettings("ru", "light")),
                    textTurnOverride = {
                        calls++
                        ai.memora.app.voice.VoiceFirstResult.Failed("model_timeout")
                    })
            }
        }
        compose.onNodeWithTag("chat_input").performTextInput(request)
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithTag("agent_text_consent_confirm").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("agent_failed_draft_note").fetchSemanticsNodes().isNotEmpty()
        }
        val restored = compose.onNodeWithTag("chat_input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        Assert.assertEquals(request, restored)
        Assert.assertEquals(1, calls)
    }

    @Test fun secretaryConsentRevocationExplainsMemoryAndKeepsTextVoiceScopesSeparate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.TEXT, true)
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, true)
            .putBoolean("gemini-persisted-memory-v1", true)
            .commit()
        var turnCalls = 0
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "chat", initialLanguage = "ru",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore,
                    textTurnOverride = {
                        turnCalls++
                        ai.memora.app.voice.VoiceFirstResult.Ready("Synthetic response", false)
                    })
            }
        }

        compose.onNodeWithTag("agent_memory_consent_toggle").performClick()
        compose.onNodeWithText("Отключить доступ к памяти?").assertIsDisplayed()
        compose.onNodeWithText("останутся на устройстве", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("agent_memory_revoke_cancel").performClick()
        Assert.assertTrue(preferences.getBoolean("gemini-persisted-memory-v1", false))

        compose.onNodeWithTag("agent_memory_consent_toggle").performClick()
        compose.onNodeWithTag("agent_memory_revoke_confirm").performClick()
        compose.runOnIdle {
            Assert.assertFalse(preferences.getBoolean("gemini-persisted-memory-v1", true))
            Assert.assertTrue(preferences.getBoolean(ai.memora.app.agent.AgentCloudConsentKeys.TEXT, false))
            Assert.assertTrue(preferences.getBoolean(ai.memora.app.agent.AgentCloudConsentKeys.VOICE, false))
        }

        compose.onNodeWithTag("agent_text_consent_toggle").performClick()
        Assert.assertFalse(preferences.getBoolean(ai.memora.app.agent.AgentCloudConsentKeys.TEXT, true))
        compose.onNodeWithTag("chat_input").performTextInput("Synthetic text after revocation")
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithTag("agent_text_consent_confirm").assertIsDisplayed()
        compose.onNodeWithText("Synthetic text after revocation").assertIsDisplayed()
        Assert.assertEquals("Revoked text consent must block provider dispatch", 0, turnCalls)
    }

    @Test fun confirmedActionCardAppearsBeforeTheFinalTextTurnReturns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE).edit()
            .putBoolean(ai.memora.app.agent.AgentCloudConsentKeys.TEXT, true).commit()
        val turnId = UUID.randomUUID().toString()
        val targetId = UUID.randomUUID().toString()
        val callbackSeen = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishTurn = kotlinx.coroutines.CompletableDeferred<ai.memora.app.voice.VoiceFirstResult>()
        val request = "Synthetic pending request"
        var providerCalls = 0
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "chat", initialLanguage = "az",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore,
                    textTurnProgressOverride = { _, onProgress ->
                        providerCalls++
                        onProgress(ai.memora.app.voice.VoiceTurnProgress.ConfirmedActions(turnId, listOf(
                            ai.memora.app.voice.VoiceTurnActionResult(
                                UUID.randomUUID().toString(), turnId, "record_money_event", "APPLIED",
                                "receipt_recorded", "finance", targetId,
                            ),
                        )))
                        callbackSeen.complete(Unit)
                        finishTurn.await()
                    })
            }
        }
        compose.onNodeWithTag("chat_input").performTextInput(request)
        compose.onNodeWithTag("chat_send").performClick()
        compose.waitUntil(5_000) { callbackSeen.isCompleted }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("action_result_${turnId}_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(request).assertExists()
        compose.onNodeWithTag("action_status_label").assertIsDisplayed()
        compose.onNodeWithTag("action_open_source").performClick()
        compose.onNodeWithTag("action_source_user_message").assertTextContains(request)
        compose.onNodeWithTag("action_source_close").performClick()
        finishTurn.complete(ai.memora.app.voice.VoiceFirstResult.Failed("synthetic_final_reply_stopped"))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("agent_confirmed_action_failure_notice").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("action_result_${turnId}_0").assertExists()
        compose.onNodeWithTag("agent_confirmed_action_failure_notice").assertIsDisplayed()
        Assert.assertEquals("A confirmed action must not be automatically sent again", 1, providerCalls)
        Assert.assertEquals("The completed request must not be restored as a resend draft", "",
            compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun secretaryDraftAndHistoryScrollSurviveUiRecreation() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val processor = ai.memora.app.voice.AgentVoiceTurnProcessor(context, agentDatabase.database)
            val scopeId = processor.conversationScopeId()
            val repository = ai.memora.app.data.protectedstore.ProtectedConversationRepository(agentDatabase.database)
            val turnIds = (0..6).map { UUID.randomUUID().toString() }
            turnIds.forEachIndexed { index, turnId ->
                val actions = if (index == 0) listOf(ai.memora.app.data.protectedstore.AgentActionResultRow(
                    UUID.randomUUID().toString(), scopeId, turnId, "create_task", "APPLIED", "task_created",
                    "task", UUID.randomUUID().toString(), 1_800_000_000_000L,
                )) else emptyList()
                repository.appendTurn(scopeId, turnId, "Synthetic history request $index", "Synthetic history answer $index",
                    1_800_000_000_000L + index * 1_000L, actions)
            }
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                    TestMemoraApp(initialRoute = "chat", initialLanguage = "az", capturePersistenceOverride = captureStore,
                        appearanceStoreOverride = appearanceStore)
                }
            }
            val oldestTurn = turnIds.first()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat_turn_${turnIds.last()}_user").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("chat_history").performScrollToNode(hasTestTag("action_result_${oldestTurn}_0"))
            compose.onNodeWithTag("action_result_${oldestTurn}_0").assertIsDisplayed()
            compose.onNodeWithTag("chat_input").performTextInput("Synthetic secretary draft")

            restoration.emulateSavedInstanceStateRestore()

            compose.onNodeWithTag("chat_input").assertTextContains("Synthetic secretary draft")
            compose.onNodeWithTag("action_result_${oldestTurn}_0").assertIsDisplayed()
        }
    }

    @Test fun exactConversationRouteShowsBothMessagesForFocusedTurn() = kotlinx.coroutines.runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val processor = ai.memora.app.voice.AgentVoiceTurnProcessor(context, agentDatabase.database)
        val scopeId = processor.conversationScopeId()
        val repository = ai.memora.app.data.protectedstore.ProtectedConversationRepository(agentDatabase.database)
        val olderTurn = UUID.randomUUID().toString()
        repository.appendTurn(scopeId, olderTurn, "Synthetic older request", "Synthetic older reply", 1_799_999_999_999L,
            listOf(ai.memora.app.data.protectedstore.AgentActionResultRow(
                UUID.randomUUID().toString(), scopeId, olderTurn, "create_task", "APPLIED", "task_created",
                "task", UUID.randomUUID().toString(), 1_799_999_999_999L,
            )))
        val focusedTurn = UUID.randomUUID().toString()
        repository.appendTurn(scopeId, focusedTurn, "Synthetic focused request", "Synthetic focused assistant reply", 1_800_000_000_001L,
            (0..2).map { index -> ai.memora.app.data.protectedstore.AgentActionResultRow(
                UUID.randomUUID().toString(), scopeId, focusedTurn, "create_task", "APPLIED", "task_created",
                "task", UUID.randomUUID().toString(), 1_800_000_000_001L + index,
            ) })
        val newerTurn = UUID.randomUUID().toString()
        repository.appendTurn(scopeId, newerTurn, "Synthetic newer request", "Synthetic newer reply", 1_800_000_000_002L,
            (0..1).map { index -> ai.memora.app.data.protectedstore.AgentActionResultRow(
                UUID.randomUUID().toString(), scopeId, newerTurn, "create_task", "APPLIED", "task_created",
                "task", UUID.randomUUID().toString(), 1_800_000_000_002L + index,
            ) })

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "conversation:$focusedTurn", initialLanguage = "az",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("chat_turn_${focusedTurn}_model").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat_turn_${focusedTurn}_user").assertIsDisplayed()
        compose.onNodeWithTag("chat_turn_${focusedTurn}_model").assertIsDisplayed()
        compose.onNodeWithText("Synthetic focused assistant reply").assertIsDisplayed()
        compose.onNodeWithTag("chat_history").performScrollToNode(hasTestTag("action_result_${focusedTurn}_0"))
        compose.onNodeWithTag("action_result_${focusedTurn}_0").assertIsDisplayed()
    }

    @Test fun textDraftGoesToInboxWithoutAi() {
        compose.setContent { TestMemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_memory").performClick()
        compose.onNodeWithTag("capture_text").performScrollTo().performClick()
        awaitCaptureReady()
        compose.onNodeWithTag("save_draft").assertIsNotEnabled()
        compose.onNodeWithTag("capture_input").performTextInput("A synthetic preview note")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A synthetic preview note").assertIsDisplayed()
        compose.onNodeWithText("Сохранено локально").assertIsDisplayed()
        screenshot("04-inbox-light")
    }

    @Test fun completingTaskChangesDoneFilter() {
        taskStore.append(ai.memora.app.domain.TaskEvent.Created("synthetic-task", "local", "owner", "command", "Synthetic task"))
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "tasks", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore, taskStoreOverride = taskStore)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic task").fetchSemanticsNodes().isNotEmpty() }
        screenshot("05-tasks-light")
        compose.onNodeWithText("Synthetic task").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Synthetic task").fetchSemanticsNodes()
            (taskStore.load() as? ai.memora.app.data.StoreRead.Ready)?.tasks?.single()?.completed == true
        }
    }

    @Test fun darkThemeAndLanguageSwitch() {
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "settings", initialTheme = "light", capturePersistenceOverride = captureStore,
                    appearanceStoreOverride = appearanceStore)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("filter_dark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("attention_enabled") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("attention_settings_error").assertDoesNotExist()
        compose.onNodeWithTag("filter_dark").performClick()
        awaitAppearance("ru", "dark")
        screenshot("06-settings-dark")
        compose.onNodeWithTag("filter_en").performClick()
        awaitAppearance("en", "dark")
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithTag("nav_chat").assertTextContains("Secretary")
        compose.onNodeWithTag("filter_az").performClick()
        awaitAppearance("az", "dark")
        compose.onNodeWithText("Görünüş").assertExists()
        compose.onNodeWithTag("nav_chat").assertTextContains("Katibə")
        compose.onNodeWithTag("filter_ru").performClick()
        awaitAppearance("ru", "dark")
        compose.onNodeWithTag("nav_chat").assertTextContains("Секретарь")
        compose.onNodeWithTag("nav_home").performClick()
        screenshot("07-home-dark")
    }

    @Test fun storedAppearanceIsAppliedAtStartup() {
        appearanceStore.write(AppearanceSettings("az", "dark"))
        compose.setContent { TestMemoraApp(initialRoute = "settings", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Görünüş").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").assertIsSelected()
        compose.onNodeWithTag("filter_dark").assertIsSelected()
    }

    @Test fun freshProfileUsesAzerbaijaniWithoutAddingDemoMemories() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fresh = AtomicAppearanceStore(File(context.cacheDir, "fresh-${UUID.randomUUID()}"))
        compose.setContent { TestMemoraApp(initialRoute = "settings", capturePersistenceOverride = captureStore, appearanceStoreOverride = fresh) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_az") and isSelected() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Görünüş").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("demo_info").assertDoesNotExist()
        compose.onNodeWithTag("memory_sensor").assertDoesNotExist()
        compose.onNodeWithTag("task_sensor").assertDoesNotExist()
        screenshot("12-home-azerbaijani")
    }

    @Test fun settingsWriteFailureRequiresReloadAndNeverShowsFalseSuccess() {
        var stored = AppearanceSettings("ru", "light")
        var failWrites = true
        val store = object : AppearanceStore {
            override fun read() = stored
            override fun write(settings: AppearanceSettings) {
                if (failWrites) throw java.io.IOException("synthetic failure")
                stored = settings
            }
        }
        compose.setContent { TestMemoraApp(initialRoute = "settings", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = store) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_az") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("settings_storage_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").assertIsNotEnabled()
        compose.onNodeWithText("Оформление").assertExists()
        failWrites = false
        compose.onNodeWithTag("settings_retry").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("filter_az") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("filter_az").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Görünüş").fetchSemanticsNodes().isNotEmpty() }
        assert(stored.language == "az")
    }

    @Test fun financeShowsEmptyStateWithoutInventedAmounts() {
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialRoute = "finance", initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("finance_empty_title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("finance_empty_title").assertExists()
        compose.onNodeWithText("230 ₼").assertDoesNotExist()
        screenshot("08-finance-light")
        compose.onNodeWithText("300 ₼").assertDoesNotExist()
    }

    @Test fun voiceInputRequiresAnExplicitStart() {
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.onNodeWithTag("nav_more").performClick()
        compose.onNodeWithTag("more_voice_capture").performScrollTo().performClick()
        compose.onNodeWithTag("audio_note_start").assertExists()
        compose.onNodeWithTag("audio_note_review").assertDoesNotExist()
        compose.onNodeWithTag("audio_note_processing").assertDoesNotExist()
        screenshot("09-voice-preview")
    }

    @Test fun draftSurvivesUiRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TestMemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_memory").performClick()
        compose.onNodeWithTag("capture_text").performScrollTo().performClick()
        awaitCaptureReady()
        compose.onNodeWithTag("capture_input").performTextInput("Synthetic saved state")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("capture_input").assertTextContains("Synthetic saved state")
        compose.onNodeWithTag("save_draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("draft_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic saved state").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun smallPhoneWithLargeText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(
            File(context.cacheDir, "ui-large-font-settings-${UUID.randomUUID()}"), AppearanceSettings("az", "light"),
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.25f)) {
                TestMemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        listOf("home", "chat", "cases", "memory", "more").forEach { destination ->
            compose.onNodeWithTag("nav_$destination").assertIsDisplayed()
        }
        screenshot("12-bottom-navigation-large-font")
        compose.onNodeWithTag("nav_memory").performClick()
        compose.onNodeWithTag("capture_text").assertExists().performScrollTo().performClick()
        compose.onNodeWithTag("capture_input").assertExists()
        screenshot("10-small-phone-large-text")
    }

    @Test @Config(qualifiers = "w960dp-h600dp-land-xhdpi")
    fun tabletLandscapeKeepsNavigationReachable() {
        compose.setContent { TestMemoraApp(initialTheme = "light", capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore) }
        compose.onNodeWithTag("nav_memory").assertIsDisplayed().performClick()
        compose.onNodeWithTag("memory_search").assertIsDisplayed()
        screenshot("11-tablet-landscape")
    }

    @Test @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun financeReportsRemainScrollableOnLandscapePhone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appearanceStore = AtomicAppearanceStore(
            File(context.cacheDir, "ui-landscape-report-settings-${UUID.randomUUID()}"), AppearanceSettings("az", "light"),
        )
        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(initialTheme = "light", initialLanguage = "az",
                    capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore)
            }
        }
        compose.onNodeWithTag("nav_more").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finance_report_source").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("more_tasks").performScrollTo().assertIsDisplayed()
        screenshot("18-finance-reports-landscape-scroll")
    }
}

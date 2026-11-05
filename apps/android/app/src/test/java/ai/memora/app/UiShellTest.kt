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
        compose.onNodeWithTag("chat_input").performScrollTo().assertIsDisplayed()
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

    @Test fun todayDashboardUsesSavedTasksCasesAndCalendarEvents() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = ai.memora.app.voice.AgentVoiceTurnProcessor(context).conversationScopeId()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Baku"))
        val due = today.plusDays(1)
        taskStore.append(ai.memora.app.domain.TaskEvent.Created("synthetic-priority", scope, "owner", "synthetic-create",
            "Synthetic priority task"))
        taskStore.append(ai.memora.app.domain.TaskEvent.Updated("synthetic-priority", scope, "owner", "synthetic-due", 1,
            "Synthetic priority task", due.atTime(9, 0).toString(), "Asia/Baku", false, null, null))
        val cases = ProtectedCaseRepository(agentDatabase.database)
        val domains = ProtectedDomainRepository(agentDatabase.database)
        cases.createCase(scope, "synthetic-open-case", "Synthetic open case", null, nextAction = "Review synthetic item",
            sourceTurnId = "00000000-0000-4000-8000-000000000301", now = java.time.Instant.now().toString())
        domains.createCalendarEvent(CalendarInput("synthetic-upcoming-event", scope, "Synthetic upcoming event",
            due.toString(), null, "Asia/Baku", "date"))

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(capturePersistenceOverride = captureStore, appearanceStoreOverride = appearanceStore, taskStoreOverride = taskStore)
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("today_task_synthetic-priority").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("today_case_synthetic-open-case").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("today_event_synthetic-upcoming-event").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("today_task_synthetic-priority").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_case_synthetic-open-case").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("today_event_synthetic-upcoming-event").performScrollTo().assertIsDisplayed()
        Assert.assertEquals(1, cases.openCaseCount(scope))
        screenshot("14-today-with-local-data")
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
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat_turn_${turnId}_model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("action_result_${turnId}_0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yadda saxlanıldı").assertIsDisplayed()
        compose.onNodeWithTag("action_result_${turnId}_1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("action_delete_not_confirmed").assertTextEquals(
            "Silinmə təsdiqlənməyib. Yaddaşı yoxlamadan əməliyyatı təkrarlamayın.")
        screenshot("15-secretary-action-card")
        compose.onAllNodesWithTag("action_open_source").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("action_source_user_message").assertExists()
        compose.onNodeWithTag("action_source_assistant_message").assertExists()
        compose.onNodeWithText("Bağla").performClick()
        compose.onNodeWithTag("action_prepare_correction").performScrollTo().performClick()
        val correctionDraft = compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        Assert.assertTrue(correctionDraft.contains(targetId))
        compose.onNodeWithTag("action_correction_draft_note").assertExists()
        compose.onAllNodesWithTag("action_open_related").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("nav_more").assertIsSelected()
        compose.onNodeWithTag("finance_screen").assertExists()
        Unit
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
        compose.onNodeWithText("Данных пока нет").assertExists()
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

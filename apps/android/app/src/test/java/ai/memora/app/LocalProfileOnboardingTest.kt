package ai.memora.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.data.AppearanceSettings
import ai.memora.app.data.AtomicAppearanceStore
import ai.memora.app.data.LocalProfileInput
import ai.memora.app.data.LocalProfileState
import ai.memora.app.data.LocalProfileViewModel
import ai.memora.app.data.ProtectedLocalProfileStore
import ai.memora.app.data.InstallDisposition
import ai.memora.app.data.LocalInstallMarker
import ai.memora.app.data.protectedstore.DatabaseKeyProvider
import ai.memora.app.agent.AgentCloudConsentKeys
import ai.memora.app.voice.AgentVoiceTurnProcessor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalProfileOnboardingTest {
    @get:Rule(order = 0) val agentDatabase = UiAgentDatabaseRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun scrollOnboardingTo(tag: String) {
        repeat(8) {
            val visible = runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
            if (visible) return
            compose.onNodeWithTag("profile_onboarding_scroll").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
            ) { scrollBy -> scrollBy(0f, 150f) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun notificationDatabaseCreationBeforeProfileInspectionKeepsFreshInstallNew() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun isolatedContext(root: File): Context = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(context.applicationInfo).apply { dataDir = root.path }
        }

        val freshRoot = File(context.cacheDir, "profile-install-notification-race-${UUID.randomUUID()}").apply { mkdirs() }
        val freshContext = isolatedContext(freshRoot)
        // MainActivity starts notification reconciliation before Compose. schedule() opens Room
        // before it checks notification permission, so this is the file-creation side effect that
        // can win the race against LocalProfileViewModel's marker inspection.
        assertTrue(freshContext.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME).createNewFile())
        val freshMarker = LocalInstallMarker(freshContext, File(freshRoot, "marker"))
        assertEquals(InstallDisposition.NEW, freshMarker.inspect())
        assertEquals(InstallDisposition.NEW, freshMarker.inspect())

        val legacyRoot = File(context.cacheDir, "profile-install-legacy-${UUID.randomUUID()}").apply { mkdirs() }
        val legacyContext = isolatedContext(legacyRoot)
        assertTrue(File(legacyContext.filesDir, "tasks-prototype.bin").createNewFile())
        val legacyMarker = LocalInstallMarker(legacyContext, File(legacyRoot, "marker"))
        assertEquals(InstallDisposition.EXISTING, legacyMarker.inspect())
    }

    @Test fun freshInstallShowsOnboardingDespiteNotificationDatabaseAndStaysCompleteAfterRestart() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "profile-onboarding-notification-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(base.applicationInfo).apply { dataDir = root.path }
        }
        // Reproduce MainActivity's ordering: notification startup has already made Room's DB file.
        assertTrue(context.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME).createNewFile())
        val marker = LocalInstallMarker(context, File(root, "marker"))
        val store = InMemoryLocalProfileStore()
        val firstLaunch = LocalProfileViewModel(context, store, "az", marker)

        compose.waitUntil(10_000) { firstLaunch.state.value != LocalProfileState.Loading }
        assertEquals(LocalProfileState.Onboarding, firstLaunch.state.value)
        assertNull(runBlocking { store.read() })

        firstLaunch.complete(LocalProfileInput("Synthetic Owner", "az", "friendly", "balanced", "Asia/Baku"))
        compose.waitUntil(10_000) { firstLaunch.state.value is LocalProfileState.Ready }
        val completed = (firstLaunch.state.value as LocalProfileState.Ready).profile
        assertEquals("Synthetic Owner", completed.displayName)
        assertTrue(completed.onboardingCompleted)
        assertEquals(InstallDisposition.EXISTING, marker.inspect())

        val nextLaunch = LocalProfileViewModel(context, store, "az", marker)
        compose.waitUntil(10_000) { nextLaunch.state.value != LocalProfileState.Loading }
        assertEquals(LocalProfileState.Ready(completed), nextLaunch.state.value)
        assertEquals(1, store.completeCalls)
    }

    @Test fun markerlessExistingProfileIsPreservedWhenDatabaseFileIsNotAnInstallSignal() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "profile-onboarding-legacy-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(base.applicationInfo).apply { dataDir = root.path }
        }
        assertTrue(context.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME).createNewFile())
        val legacyProfile = ai.memora.app.data.protectedstore.LocalProfileRow(
            "synthetic-existing-profile", "Synthetic Legacy", "ru", "direct", "concise", "Europe/Moscow",
            1, true, null, 10L, 20L,
        )
        val store = InMemoryLocalProfileStore(legacyProfile)
        val marker = LocalInstallMarker(context, File(root, "marker"))
        assertEquals(InstallDisposition.NEW, marker.inspect()) // The DB filename alone is intentionally ambiguous.

        val viewModel = LocalProfileViewModel(context, store, "az", marker)
        compose.waitUntil(10_000) { viewModel.state.value != LocalProfileState.Loading }
        assertEquals(LocalProfileState.Ready(legacyProfile), viewModel.state.value)
        assertEquals(legacyProfile, runBlocking { store.read() })
        assertEquals(0, store.completeCalls)
        assertEquals(InstallDisposition.EXISTING, marker.inspect())
    }

    @Test fun encryptedLocalStateIsExcludedFromCloudBackupAndDeviceTransfer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(false, appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0)

        val excludedBySection = mutableMapOf<String, MutableSet<String>>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        try {
            var section: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> section = parser.name
                    "exclude" -> {
                        val domain = requireNotNull(parser.getAttributeValue(null, "domain"))
                        val path = requireNotNull(parser.getAttributeValue(null, "path"))
                        excludedBySection.getOrPut(requireNotNull(section)) { mutableSetOf() }.add("$domain:$path")
                    }
                }
            }
        } finally {
            parser.close()
        }
        val expectedExcludedDomains = setOf(
            "root:.", "file:.", "database:.", "sharedpref:.", "external:.",
            "device_root:.", "device_file:.", "device_database:.", "device_sharedpref:.",
        )
        assertEquals(expectedExcludedDomains, excludedBySection["cloud-backup"])
        assertEquals(expectedExcludedDomains, excludedBySection["device-transfer"])
        assertEquals(context.noBackupFilesDir, File(context.noBackupFilesDir, DatabaseKeyProvider.KEY_ENVELOPE_NAME).parentFile)
    }

    @Test fun completedLocalProfilePreferencesRemainEditableAndEncryptedInRoom() = runBlocking {
        val store = ProtectedLocalProfileStore(agentDatabase.database)
        val initial = store.complete(ai.memora.app.data.LocalProfileInput(null, "az", "friendly", "balanced", "Asia/Baku"))
        val updated = store.updateProfile(initial.localProfileId,
            ai.memora.app.data.LocalProfileInput("Synthetic User", "ru", "direct", "concise", "Europe/Moscow"))

        assertEquals(initial.localProfileId, updated.localProfileId)
        assertEquals("Synthetic User", updated.displayName)
        assertEquals("ru", updated.language)
        assertEquals("direct", updated.toneStyle)
        assertEquals("concise", updated.responseStyle)
        assertEquals("Europe/Moscow", updated.timeZoneId)
        assertEquals(null, updated.remoteAccountId)
        assertTrue(updated.onboardingCompleted)
        assertEquals(updated, store.read())
    }

    @Test fun cleanProfileStartsEmptyAndOnboardingWritesOnlyProfilePreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = InMemoryLocalProfileStore()
        val consent = context.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
        consent.edit().clear().commit()
        assertNull(runBlocking { store.read() })

        compose.setContent {
            CompositionLocalProvider(LocalAgentDatabaseOverride provides agentDatabase.database) {
                TestMemoraApp(
                    initialLanguage = "az",
                    appearanceStoreOverride = AtomicAppearanceStore(
                        File(context.cacheDir, "onboarding-settings"), AppearanceSettings("az", "system"),
                    ),
                    profileStoreOverride = store,
                )
            }
        }

        scrollOnboardingTo("profile_display_name")
        compose.onNodeWithTag("profile_display_name").assertIsDisplayed().performTextInput("Samir")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("profile_start") and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        scrollOnboardingTo("profile_language_ru")
        compose.onNodeWithTag("profile_language_ru").assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("profile_language_ru") and isSelected()).assertExists()
        scrollOnboardingTo("profile_tone_direct")
        compose.onNodeWithTag("profile_tone_direct").assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("profile_tone_direct") and isSelected()).assertExists()
        scrollOnboardingTo("profile_style_concise")
        compose.onNodeWithTag("profile_style_concise").assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("profile_style_concise") and isSelected()).assertExists()
        scrollOnboardingTo("profile_timezone_Europe_Moscow")
        compose.onNodeWithTag("profile_timezone_Europe_Moscow").assertIsDisplayed().performClick()
        compose.onNode(hasTestTag("profile_timezone_Europe_Moscow") and isSelected()).assertExists()
        scrollOnboardingTo("profile_start")
        compose.onNodeWithTag("profile_start").assertIsDisplayed()
        compose.onNodeWithTag("profile_start").performClick()
        compose.waitForIdle()
        assertEquals(1, store.completeCalls)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("voice_first_button").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("profile_save_error").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("profile_loading").fetchSemanticsNodes().isNotEmpty()
        }
        val ready = compose.onAllNodesWithTag("voice_first_button").fetchSemanticsNodes().isNotEmpty()
        if (!ready) throw AssertionError("onboarding did not finish; completeCalls=${store.completeCalls}; screen=${compose.onRoot().printToString()}")
        compose.onNodeWithTag("profile_save_error").assertDoesNotExist()
        compose.onNodeWithTag("profile_display_name").assertDoesNotExist()

        val profile = requireNotNull(runBlocking { store.read() })
        assertEquals("Samir", profile.displayName)
        assertEquals("ru", profile.language)
        assertEquals("direct", profile.toneStyle)
        assertEquals("concise", profile.responseStyle)
        assertEquals("Europe/Moscow", profile.timeZoneId)
        assertEquals(1, profile.onboardingVersion)
        assertEquals(true, profile.onboardingCompleted)
        assertNull(profile.remoteAccountId)
        val dao = agentDatabase.database.protectedStoreDao()
        runBlocking {
            assertEquals(emptyList<ai.memora.app.data.protectedstore.PersonRow>(), dao.people())
            assertEquals(emptyList<ai.memora.app.data.protectedstore.MoneyEventRow>(), dao.moneyEvents())
            assertEquals(emptyList<ai.memora.app.data.protectedstore.MemorySourceRow>(), dao.sources())
            assertEquals(emptyList<ai.memora.app.data.protectedstore.TaskEventRow>(), dao.taskEvents())
            assertEquals(emptyList<ai.memora.app.data.protectedstore.DomainObjectRow>(), dao.activeObjects("synthetic-scope"))
            assertEquals(emptyList<ai.memora.app.data.protectedstore.AgentConversationMessageRow>(),
                dao.recentConversationMessages("synthetic-scope", 100))
        }
        assertEquals(emptySet<String>(), consent.all.keys)
    }

    @Test fun revokingCloudConsentKeepsTheExistingLocalConversationScope() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val consent = context.getSharedPreferences("voice-first-consent", Context.MODE_PRIVATE)
        val identity = context.getSharedPreferences("agent-runtime-identity", Context.MODE_PRIVATE)
        val legacyScopeId = UUID.randomUUID().toString()
        consent.edit().clear().putString("scope-id", legacyScopeId).putBoolean(AgentCloudConsentKeys.VOICE, true).commit()
        identity.edit().clear().commit()

        val processor = AgentVoiceTurnProcessor(context, agentDatabase.database)
        assertEquals(legacyScopeId, processor.conversationScopeId())
        assertEquals(legacyScopeId, identity.getString("scope-id", null))
        assertNull(consent.getString("scope-id", null))

        consent.edit().clear().commit()
        assertEquals(legacyScopeId, processor.conversationScopeId())
        assertTrue(consent.all.isEmpty())
    }

    @Test fun encryptedProfileStorePersistsPreferencesWithoutSeedingDomainFacts() = runBlocking {
        val store = ProtectedLocalProfileStore(agentDatabase.database)
        assertNull(store.read())

        val profile = store.complete(ai.memora.app.data.LocalProfileInput("Synthetic user", "az", "direct", "detailed", "Asia/Baku"))

        assertEquals("Synthetic user", profile.displayName)
        assertEquals("az", profile.language)
        assertEquals("direct", profile.toneStyle)
        assertEquals("detailed", profile.responseStyle)
        assertEquals("Asia/Baku", profile.timeZoneId)
        assertEquals(null, profile.remoteAccountId)
        val dao = agentDatabase.database.protectedStoreDao()
        assertNotNull(dao.localProfile())
        assertTrue(dao.people().isEmpty())
        assertTrue(dao.moneyEvents().isEmpty())
        assertTrue(dao.sources().isEmpty())
        assertTrue(dao.taskEvents().isEmpty())
    }
}

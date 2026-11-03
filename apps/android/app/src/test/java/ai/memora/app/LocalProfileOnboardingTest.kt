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
import ai.memora.app.data.ProtectedLocalProfileStore
import ai.memora.app.data.InstallDisposition
import ai.memora.app.data.LocalInstallMarker
import ai.memora.app.data.protectedstore.DatabaseKeyProvider
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

    @Test fun installMarkerInspectsBeforeFreshDatabaseAndRecognizesExistingDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun isolatedContext(root: File): Context = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(context.applicationInfo).apply { dataDir = root.path }
        }

        val freshRoot = File(context.cacheDir, "profile-install-fresh-${UUID.randomUUID()}").apply { mkdirs() }
        val freshContext = isolatedContext(freshRoot)
        val freshMarker = LocalInstallMarker(freshContext, File(freshRoot, "marker"))
        assertEquals(InstallDisposition.NEW, freshMarker.inspect())
        assertTrue(freshContext.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME).createNewFile())
        assertEquals(InstallDisposition.NEW, freshMarker.inspect())

        val existingRoot = File(context.cacheDir, "profile-install-existing-${UUID.randomUUID()}").apply { mkdirs() }
        val existingContext = isolatedContext(existingRoot)
        assertTrue(existingContext.getDatabasePath(DatabaseKeyProvider.DATABASE_NAME).createNewFile())
        val existingMarker = LocalInstallMarker(existingContext, File(existingRoot, "marker"))
        assertEquals(InstallDisposition.EXISTING, existingMarker.inspect())
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

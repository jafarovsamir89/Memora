package ai.memora.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppearanceSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun settingsSurviveStoreRecreationAndPreserveBothFields() {
        val file = File(folder.root, "appearance")
        val store = AtomicAppearanceStore(file)
        assertEquals(AppearanceSettings(), store.read())
        store.write(AppearanceSettings("az", "dark"))
        assertEquals(AppearanceSettings("az", "dark"), AtomicAppearanceStore(file).read())
        store.write(AppearanceSettings("az", "light"))
        assertEquals(AppearanceSettings("az", "light"), AtomicAppearanceStore(file).read())
    }

    @Test fun malformedOrFutureSettingsAreNeverSilentlyReset() {
        val file = File(folder.root, "appearance")
        for (text in listOf("MEMORA_SETTINGS_2\naz\ndark\n", "MEMORA_SETTINGS_1\nxx\ndark\n", "MEMORA_SETTINGS_1\naz\ndark\nextra", "x".repeat(257))) {
            file.writeText(text)
            try { AtomicAppearanceStore(file).read(); fail("Expected rejection") } catch (_: IOException) { }
            assertEquals(text, file.readText())
        }
    }

    @Test fun interruptedFirstWriteIsNotMistakenForFreshDefaults() {
        val file = File(folder.root, "appearance")
        File(file.path + ".new").writeText("partial")
        try { AtomicAppearanceStore(file).read(); fail("Expected rejection") } catch (_: IOException) { }
    }

    @Test fun invalidPresentationValuesAreRejected() {
        try { AppearanceSettings("tr", "dark"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        try { AppearanceSettings("az", "sepia"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun legacyBackupIsRecoveredBeforeSavingNewSettings() {
        val file = File(folder.root, "appearance")
        file.writeText("MEMORA_SETTINGS_1\nen\nlight\n")
        File(file.path + ".bak").writeText("MEMORA_SETTINGS_1\naz\ndark\n")
        val store = AtomicAppearanceStore(file)
        assertEquals(AppearanceSettings("az", "dark"), store.read())
        store.write(AppearanceSettings("ru", "light"))
        assertEquals(AppearanceSettings("ru", "light"), AtomicAppearanceStore(file).read())
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test fun failedStagingWritePreservesCommittedSettingsAndAllowsRetry() {
        val file = File(folder.root, "appearance")
        val store = AtomicAppearanceStore(file)
        store.write(AppearanceSettings("az", "dark"))
        val pending = File(file.path + ".new").apply { mkdir() }
        val obstruction = File(pending, "obstruction").apply { writeText("synthetic") }
        try { store.write(AppearanceSettings("en", "light")); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(AppearanceSettings("az", "dark"), AtomicAppearanceStore(file).read())
        assertTrue(obstruction.delete())
        assertTrue(pending.delete())
        store.write(AppearanceSettings("en", "light"))
        assertEquals(AppearanceSettings("en", "light"), AtomicAppearanceStore(file).read())
    }
}

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
}

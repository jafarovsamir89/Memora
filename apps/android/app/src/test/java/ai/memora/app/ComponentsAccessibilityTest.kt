package ai.memora.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.MemoraTheme
import ai.memora.app.ui.PrimaryButton
import ai.memora.app.ui.StatusBadge
import ai.memora.app.ui.MemoraStatusTone
import ai.memora.app.ui.VoiceInputButton
import ai.memora.app.ui.ErrorState
import ai.memora.app.ui.LoadingState
import ai.memora.app.ui.MoreDestinationCard
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.MemoraSpacing
import ai.memora.app.ui.MemoraBottomNavigation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ComponentsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun primaryAndVoiceActionsKeepFortyEightDpTargetsAtLargeFont() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val minimumTouchSizePx = 48f * context.resources.displayMetrics.density
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalStrings provides LocalResources.current,
                LocalDensity provides Density(currentDensity.density, 1.3f)) {
                MemoraTheme("light") {
                    Column {
                        PrimaryButton("Uzun təsdiq düyməsinin mətni", {}, testTag = "large_primary")
                        VoiceInputButton("Səsli qeydi başlat", {}, readyStateLabel = "Hazır",
                            activeStateLabel = "Dinləyir", loadingStateLabel = "Göndərilir", testTag = "large_voice")
                    }
                }
            }
        }
        compose.onNodeWithTag("large_primary").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("large_voice").assertIsDisplayed().assertHasClickAction()
        val primaryBounds = compose.onNodeWithTag("large_primary").fetchSemanticsNode().boundsInRoot
        val voiceBounds = compose.onNodeWithTag("large_voice").fetchSemanticsNode().boundsInRoot
        assertTrue("primary button touch target is below 48dp: ${primaryBounds.height}px",
            primaryBounds.height >= minimumTouchSizePx)
        assertTrue("voice button touch target is below 48dp: ${voiceBounds.height}px",
            voiceBounds.height >= minimumTouchSizePx)
    }

    @Test fun statusAndAccessibleProgressAndErrorStatesRenderWithoutDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides context.resources) {
                MemoraTheme("dark") {
                    Column {
                        StatusBadge("Təsdiq gözləyir", MemoraStatusTone.WARNING, "component_status")
                        LoadingState("Məlumatlar yüklənir", testTag = "component_loading")
                        ErrorState("Xəta", "Yenidən cəhd et", retryLabel = "Təkrar yoxla", onRetry = {},
                            testTag = "component_error")
                    }
                }
            }
        }
        compose.onNodeWithTag("component_status").assertIsDisplayed()
        compose.onNodeWithTag("component_loading").assertIsDisplayed()
        compose.onNodeWithTag("component_error").assertIsDisplayed()
        compose.onNodeWithTag("component_error_retry").assertHasClickAction()
    }

    @Test fun azerbaijaniMoreCardsAndFiveTabLabelsStayInBoundsAtLargeFont() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val azContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("az-AZ"))
        })
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalStrings provides azContext.resources,
                LocalDensity provides Density(currentDensity.density, 1.3f)) {
                MemoraTheme("light") {
                    Column {
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MemoraSpacing.sm)) {
                            MoreDestinationCard(Glyph.Person, "Əlaqələr və layihələr", "Uzun Azərbaycan dilində bölmə təsviri və qeydlər", 1,
                                "more_people_large") {}
                            MoreDestinationCard(Glyph.Calendar, "Görüşlər və xatırlatmalar", "Uzun təsvir Azərbaycan dilində", 0,
                                "more_calendar_large") {}
                        }
                        MemoraBottomNavigation(AppDestination.MORE) {}
                    }
                }
            }
        }

        listOf("home", "chat", "cases", "memory", "more").forEach { key ->
            val bounds = compose.onNodeWithTag("nav_label_$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("AZ navigation label $key is clipped/outside root: $bounds / $root",
                bounds.width > 0f && bounds.height > 0f && root.width > 0f && root.height > 0f &&
                    bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom)
        }
        compose.onNodeWithText("Əlaqələr və layihələr").assertIsDisplayed()
        compose.onNodeWithTag("more_people_large").assertHasClickAction()
        compose.onNodeWithTag("more_calendar_large").assertHasClickAction()
    }
}

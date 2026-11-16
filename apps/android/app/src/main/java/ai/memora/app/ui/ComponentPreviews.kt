package ai.memora.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.memora.app.R
import java.util.Locale

private val previewAction = ActionResultPresentation(
    statusLabel = "Yadda saxlanıldı",
    statusTone = MemoraStatusTone.SUCCESS,
    relatedActionLabel = "Əlaqəli işi aç",
    sourceActionLabel = "Mənbəni aç",
    correctionActionLabel = "Düzəliş hazırla",
)

@Composable
private fun PreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val azContext = context.createConfigurationContext(Configuration(LocalConfiguration.current).apply {
        setLocale(Locale.forLanguageTag("az-AZ"))
    })
    CompositionLocalProvider(LocalStrings provides azContext.resources) {
        MemoraTheme(if (dark) "dark" else "light") {
            Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.ink) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
            }
        }
    }
}

@Preview(name = "Memora · AZ · Light", showBackground = true)
@Composable
fun MemoraComponentsLightPreview() = PreviewFrame(dark = false) {
    PrimaryButton("Davam et", {}, testTag = "preview_primary")
    SecondaryButton("Əlaqəli işi aç", {}, testTag = "preview_secondary")
    VoiceInputButton("Danışmağa başla", {}, readyStateLabel = "Hazır", activeStateLabel = "Dinləyir",
        loadingStateLabel = "Göndərilir", testTag = "preview_voice")
    StatusBadge("Yadda saxlanıldı", MemoraStatusTone.SUCCESS)
    ActionResultCard(previewAction, {}, {})
    PersonCard(PersonCardContent("Əli İsmayılov", "Layihə üzrə əlaqə", "Aktiv"), {})
    CaseCard(CaseCardContent("Təchizat müqaviləsini yenilə", "Gözləyir", MemoraStatusTone.WARNING,
        "Tərəfdaşdan cavab gözlənilir", "Növbəti addım", "Sabah yenidən əlaqə saxla"), {})
    EmptyState("Hələ qeyd yoxdur", "Söhbət etdikcə Memora vacib məlumatları burada saxlayacaq.", Glyph.Memory)
    ErrorState("Yükləmək mümkün olmadı", "Bağlantını yoxla və yenidən cəhd et.",
        retryLabel = "Yenidən yoxla", onRetry = {})
    LoadingState("Məlumatlar yüklənir…")
    Timeline(listOf(TimelineItem("preview-one", "Müqavilə layihəsi yaradıldı", "15 noyabr 2026",
        "Mənbə: Katibə", sourceActionLabel = "Mənbəni aç")))
}

@Preview(name = "Memora · AZ · Dark · 1.3×", showBackground = true, fontScale = 1.3f)
@Composable
fun MemoraComponentsDarkLargeTextPreview() = PreviewFrame(dark = true) {
    PrimaryButton("Uzun təsdiq düyməsi mətni", {}, testTag = "preview_primary_large")
    VoiceInputButton("Səsli qeydi başlat", {}, active = true,
        readyStateLabel = "Hazır", activeStateLabel = "Dinləyir", loadingStateLabel = "Göndərilir")
    CaseCard(CaseCardContent("Uzun adlı müştəri işi və əlaqəli layihə", "Təsdiq gözləyir",
        MemoraStatusTone.WARNING, "Mənbə və tərəfdaşla bağlı geniş izah burada bir neçə sətirdə görünür.",
        "Növbəti addım", "Sənədləri yenidən nəzərdən keçir"), {})
    ConfirmationSheet(true, "Dəyişikliyi təsdiqlə", "Bu nümunə yalnız lokal UI preview məlumatıdır.",
        "Təsdiqlə", "Geri qayıt", {}, {})
}

@Preview(name = "Memora · confirmation · Light", showBackground = true)
@Composable
fun MemoraConfirmationSheetPreview() = PreviewFrame(dark = false) {
    ConfirmationSheet(true, "Dəyişikliyi təsdiqlə", "Bu əməliyyat lokal qeydə tətbiq olunacaq.",
        "Təsdiqlə", "Ləğv et", {}, {})
}

/** Deterministic, in-memory-only gallery used by the ADB pixel-diff task. */
@Composable
internal fun ComponentGalleryScreen(component: String, theme: String = "light") {
    val context = LocalContext.current
    val azContext = context.createConfigurationContext(Configuration(LocalConfiguration.current).apply {
        setLocale(Locale.forLanguageTag("az-AZ"))
    })
    CompositionLocalProvider(LocalStrings provides azContext.resources) {
        MemoraTheme(theme) {
            Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.ink) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ScreenHeading("Memora · komponentlər", "Azərbaycan dili · vizual regresiya")
                    when (component) {
                        "primary" -> PrimaryButton("Davam et", {}, testTag = "golden_primary")
                        "primary_loading" -> PrimaryButton("Məlumat saxlanılır", {}, loading = true, testTag = "golden_primary_loading")
                        "primary_error" -> PrimaryButton("Yenidən cəhd et", {}, errorMessage = "Əməliyyat alınmadı", testTag = "golden_primary_error")
                        "primary_disabled" -> PrimaryButton("Mövcud deyil", {}, enabled = false, testTag = "golden_primary_disabled")
                        "secondary" -> SecondaryButton("Mənbəni aç", {}, testTag = "golden_secondary")
                        "secondary_loading" -> SecondaryButton("Gözlə…", {}, loading = true, testTag = "golden_secondary_loading")
                        "voice" -> VoiceInputButton("Danışmağa başla", {}, readyStateLabel = "Hazır", activeStateLabel = "Dinləyir", loadingStateLabel = "Göndərilir")
                        "voice_active" -> VoiceInputButton("Dinləmə davam edir", {}, active = true,
                            readyStateLabel = "Hazır", activeStateLabel = "Dinləyir", loadingStateLabel = "Göndərilir")
                        "voice_loading" -> VoiceInputButton("Səs emal olunur", {}, loading = true,
                            readyStateLabel = "Hazır", activeStateLabel = "Dinləyir", loadingStateLabel = "Göndərilir")
                        "status_success" -> StatusBadge("Yadda saxlanıldı", MemoraStatusTone.SUCCESS)
                        "status_warning" -> StatusBadge("Təsdiq gözləyir", MemoraStatusTone.WARNING)
                        "status_error" -> StatusBadge("Xəta baş verdi", MemoraStatusTone.ERROR)
                        "action" -> ActionResultCard(previewAction, {}, {})
                        "action_warning" -> ActionResultCard(previewAction.copy(statusLabel = "Təsdiq gözləyir",
                            statusTone = MemoraStatusTone.WARNING, supportingText = "Heç bir dəyişiklik edilmədi."), null, {})
                        "person" -> PersonCard(PersonCardContent("Əli İsmayılov", "Layihə üzrə əlaqə", "Aktiv"), {})
                        "case" -> CaseCard(CaseCardContent("Təchizat müqaviləsini yenilə", "Gözləyir", MemoraStatusTone.WARNING,
                            "Tərəfdaşdan cavab gözlənilir", "Növbəti addım", "Sabah yenidən əlaqə saxla"), {})
                        "empty" -> EmptyState("Hələ qeyd yoxdur", "Söhbət etdikcə Memora vacib məlumatları burada saxlayacaq.", Glyph.Memory)
                        "error" -> ErrorState("Yükləmək mümkün olmadı", "Bağlantını yoxla və yenidən cəhd et.",
                            retryLabel = "Yenidən yoxla", onRetry = {})
                        "loading" -> LoadingState("Məlumatlar yüklənir…")
                        "confirmation" -> ConfirmationSheet(true, "Dəyişikliyi təsdiqlə",
                            "Bu əməliyyat lokal qeydə tətbiq olunacaq.", "Təsdiqlə", "Ləğv et", {}, {})
                        "timeline" -> Timeline(listOf(
                            TimelineItem("one", "Müqavilə layihəsi yaradıldı", "15 noyabr 2026", "Mənbə: Katibə",
                                sourceActionLabel = "Mənbəni aç"),
                            TimelineItem("two", "Tərəfdaş cavabı əlavə edildi", "16 noyabr 2026", "Danışıq qeydi saxlanıldı"),
                        ))
                        "more_cards" -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MoreDestinationCard(Glyph.Person, "Uzun adlı insan bölməsi", "Əlaqələr və uzun təsvirlər Azərbaycan dilində", 1, "golden_more_people") {}
                            MoreDestinationCard(Glyph.Calendar, "Təqvim və görüşlər", "Planlaşdırılmış görüşlər və xatırlatmalar", 0, "golden_more_calendar") {}
                        }
                        "navigation" -> MemoraBottomNavigation(ai.memora.app.AppDestination.MORE, {})
                        else -> ErrorState("Naməlum preview", "Komponent qalereyasında belə bir açar yoxdur.")
                    }
                }
            }
        }
    }
}

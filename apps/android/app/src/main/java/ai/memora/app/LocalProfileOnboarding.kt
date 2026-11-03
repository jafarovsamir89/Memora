package ai.memora.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.memora.app.data.LocalProfileInput
import ai.memora.app.ui.BrandMark
import ai.memora.app.ui.Glyph
import ai.memora.app.ui.IconBubble
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s
import ai.memora.app.R

@Composable
internal fun LocalProfileLoadingScreen() {
    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandMark()
            Column {
                Text("memora", fontWeight = FontWeight.SemiBold)
                Text(s(R.string.onboarding_saving), color = colors.muted)
            }
        }
        CircularProgressIndicator(modifier = Modifier.testTag("profile_loading"))
    }
}

@Composable
internal fun LocalProfileErrorScreen(messageId: Int, retry: () -> Unit) {
    ScreenColumn {
        BrandMark()
        ScreenHeading(s(R.string.onboarding_load_error), s(messageId))
        Button(onClick = retry, modifier = Modifier.fillMaxWidth().testTag("profile_retry")) {
            Text(s(R.string.onboarding_retry))
        }
    }
}

@Composable
internal fun LocalProfileOnboardingScreen(
    initialLanguage: String,
    initialInput: LocalProfileInput? = null,
    canContinue: Boolean,
    onLanguageChange: (String) -> Unit,
    saving: Boolean,
    saveError: Boolean,
    onStart: (LocalProfileInput) -> Unit,
) {
    var displayName by rememberSaveable(initialInput?.displayName) { mutableStateOf(initialInput?.displayName.orEmpty()) }
    var language by rememberSaveable(initialLanguage) { mutableStateOf(initialLanguage) }
    var tone by rememberSaveable(initialInput?.toneStyle) { mutableStateOf(initialInput?.toneStyle ?: "friendly") }
    var style by rememberSaveable(initialInput?.responseStyle) { mutableStateOf(initialInput?.responseStyle ?: "balanced") }
    var timeZone by rememberSaveable(initialInput?.timeZoneId) { mutableStateOf(initialInput?.timeZoneId ?: "Asia/Baku") }

    ScreenColumn(Modifier.testTag("profile_onboarding_scroll")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandMark()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s(R.string.onboarding_eyebrow), style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = colors.accent)
                Text("memora", style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    color = colors.ink, fontWeight = FontWeight.SemiBold)
            }
        }
        ScreenHeading(s(R.string.onboarding_title), s(R.string.onboarding_body))

        MemoraCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBubble(Glyph.Memory)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(s(R.string.onboarding_privacy_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.ink)
                    Text(s(R.string.onboarding_privacy_body), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.muted)
                }
            }
        }

        MemoraCard(tint = colors.softAccent) {
            Text(s(R.string.onboarding_examples_title), style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = colors.ink)
            Text(s(R.string.onboarding_examples_body), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.muted)
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { if (it.length <= 60) displayName = it },
            label = { Text(s(R.string.onboarding_name)) },
            placeholder = { Text(s(R.string.onboarding_name_hint)) },
            singleLine = true,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().testTag("profile_display_name"),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s(R.string.onboarding_language), style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = colors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("az" to "Azərbaycan", "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                    FilterChip(
                        selected = language == code,
                        onClick = { language = code; onLanguageChange(code) },
                        enabled = !saving && canContinue,
                        label = { Text(label) },
                        modifier = Modifier.testTag("profile_language_$code"),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s(R.string.onboarding_tone), style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = colors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("friendly" to R.string.onboarding_tone_friendly, "direct" to R.string.onboarding_tone_direct)
                    .forEach { (value, labelId) ->
                        FilterChip(
                            selected = tone == value,
                            onClick = { tone = value },
                            enabled = !saving,
                            label = { Text(s(labelId)) },
                            modifier = Modifier.testTag("profile_tone_$value"),
                        )
                    }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s(R.string.onboarding_style), style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = colors.ink)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "concise" to R.string.onboarding_style_concise,
                    "balanced" to R.string.onboarding_style_balanced,
                    "detailed" to R.string.onboarding_style_detailed,
                ).forEach { (value, labelId) ->
                    FilterChip(
                        selected = style == value,
                        onClick = { style = value },
                        enabled = !saving,
                        label = { Text(s(labelId)) },
                        modifier = Modifier.testTag("profile_style_$value"),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s(R.string.onboarding_timezone), style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                color = colors.ink)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Asia/Baku", "Europe/Moscow", "UTC").forEach { zone ->
                    FilterChip(
                        selected = timeZone == zone,
                        onClick = { timeZone = zone },
                        enabled = !saving,
                        label = { Text(zone) },
                        modifier = Modifier.testTag("profile_timezone_${zone.replace('/', '_')}"),
                    )
                }
            }
        }

        if (saveError) Text(s(R.string.onboarding_save_error), color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("profile_save_error"))

        Button(
            onClick = { onStart(LocalProfileInput(displayName.trim().takeIf(String::isNotEmpty), language, tone, style, timeZone)) },
            enabled = !saving && canContinue,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("profile_start"),
        ) {
            if (saving) CircularProgressIndicator()
            else Text(s(R.string.onboarding_start))
        }
    }
}

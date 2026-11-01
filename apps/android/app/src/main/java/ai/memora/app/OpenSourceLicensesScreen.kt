package ai.memora.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import ai.memora.app.ui.s

@Composable
internal fun OpenSourceLicensesScreen() {
    val context = LocalContext.current
    val license = remember(context) {
        context.resources.openRawResource(R.raw.sqlcipher_community_license)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    ScreenColumn {
        ScreenHeading(s(R.string.open_source_licenses), s(R.string.open_source_licenses_hint))
        Text(
            text = "SQLCipher Community Edition — Copyright (c) 2008-2026, ZETETIC, LLC",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.muted,
            modifier = Modifier.padding(bottom = 24.dp).testTag("sqlcipher_license_text"),
        )
        Text(
            text = s(R.string.open_source_sqlcipher_components),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(bottom = 32.dp),
        )
    }
}

package ai.memora.app.ui

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.memora.app.R

val LocalStrings = staticCompositionLocalOf<Resources> { error("Missing localized resources") }
@Composable fun s(id: Int): String = LocalStrings.current.getString(id)

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content,
    )
}

@Composable
fun ScreenHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = colors.ink,
            modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val ink = colors.accent
    Canvas(modifier.size(34.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .13f, h * .78f)
            lineTo(w * .13f, h * .36f)
            cubicTo(w * .13f, h * .02f, w * .49f, h * .02f, w * .49f, h * .36f)
            lineTo(w * .49f, h * .70f)
            moveTo(w * .49f, h * .36f)
            cubicTo(w * .49f, h * .02f, w * .87f, h * .02f, w * .87f, h * .36f)
            lineTo(w * .87f, h * .78f)
        }
        drawPath(path, ink, style = Stroke(3.3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawCircle(ink.copy(alpha = .45f), 2.dp.toPx(), Offset(w * .87f, h * .96f))
    }
}

@Composable
fun MemoraCard(
    modifier: Modifier = Modifier,
    tint: Color = colors.card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = tint, shape = RoundedCornerShape(24.dp),
    ) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
fun IconBubble(icon: ImageVector, tone: Int = 0, size: Int = 44) {
    val bg = when (tone) { 1 -> colors.softGreen; 2 -> colors.softAmber; else -> colors.softAccent }
    val fg = when (tone) { 1 -> colors.green; 2 -> colors.amber; else -> colors.onSoftAccent }
    Box(Modifier.size(size.dp).background(bg, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp), tint = fg)
    }
}

@Composable
fun Pill(label: String, tone: Int = 0) {
    val bg = when (tone) { 1 -> colors.softGreen; 2 -> colors.softAmber; else -> colors.softAccent }
    val fg = when (tone) { 1 -> colors.green; 2 -> colors.amber; else -> colors.onSoftAccent }
    Text(label, Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
        color = fg, style = MaterialTheme.typography.labelSmall)
}

@Composable
fun SectionHeading(title: String, action: (() -> Unit)? = null, badge: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (badge != null) Pill(badge)
        if (action != null) TextButton(onClick = action) {
            Text(s(R.string.all)); Icon(Glyph.Chevron, null, Modifier.size(15.dp))
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, subtitle: String, tone: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Icon(Glyph.Chevron, null, Modifier.size(17.dp), tint = colors.muted)
    }
}

@Composable
fun EmptyState(title: String, body: String, icon: ImageVector = Glyph.Search) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBubble(icon, size = 64)
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Waveform(modifier: Modifier = Modifier) {
    val ink = colors.accent
    Canvas(modifier.height(72.dp).fillMaxWidth()) {
        val values = listOf(12, 18, 10, 26, 38, 21, 45, 58, 32, 46, 24, 51, 66, 43, 27, 46, 55, 30, 42, 21, 12, 24, 14)
        val gap = size.width / (values.size + 1)
        values.forEachIndexed { i, height ->
            val half = height * size.height / 150
            drawLine(ink.copy(alpha = if (i % 3 == 0) .4f else .8f),
                Offset(gap * (i + 1), size.height / 2 - half),
                Offset(gap * (i + 1), size.height / 2 + half),
                strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

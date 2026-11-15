package ai.memora.app.ui

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.R
import ai.memora.app.AppDestination

val LocalStrings = staticCompositionLocalOf<Resources> { error("Missing localized resources") }
@Composable fun s(id: Int): String = LocalStrings.current.getString(id)

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .then(modifier).padding(horizontal = MemoraSpacing.xl)
            .padding(top = MemoraSpacing.screenTop, bottom = MemoraSpacing.screenBottom),
        verticalArrangement = Arrangement.spacedBy(MemoraSpacing.lg), content = content,
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
    contentPadding: androidx.compose.ui.unit.Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MemoraShapes.card
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).then(
            if (onClick != null) Modifier.clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ) else Modifier,
        ),
        color = tint,
        shape = shape,
        border = if (onClick != null) BorderStroke(1.dp, colors.line) else null,
        tonalElevation = if (onClick != null) 1.dp else 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(MemoraSpacing.sm), content = content)
    }
}

@Composable
fun IconBubble(icon: ImageVector, tone: Int = 0, size: Int = 44) {
    val (bg, fg) = when (tone) {
        1 -> colors.softMint to colors.green
        2 -> colors.softAmber to colors.amber
        3 -> colors.softMint to colors.onSoftMint
        else -> colors.softAccent to colors.onSoftAccent
    }
    Box(Modifier.size(size.dp).background(bg, MemoraShapes.control), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = fg)
    }
}

@Composable
fun Pill(label: String, tone: Int = 0) {
    val (bg, fg) = when (tone) {
        1 -> colors.softMint to colors.green
        2 -> colors.softAmber to colors.amber
        3 -> colors.softMint to colors.onSoftMint
        else -> colors.softAccent to colors.onSoftAccent
    }
    Text(label, Modifier.background(bg, MemoraShapes.pill).padding(horizontal = 11.dp, vertical = 6.dp),
        color = fg, style = MaterialTheme.typography.labelSmall)
}

@Composable
fun SectionHeading(title: String, action: (() -> Unit)? = null, badge: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), color = colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (badge != null) Pill(badge)
        if (action != null) TextButton(onClick = action) {
            Text(s(R.string.all)); Icon(Glyph.Chevron, null, Modifier.size(15.dp))
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, subtitle: String, tone: Int = 0, testTag: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(MemoraShapes.control)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = MemoraSpacing.xs, horizontal = MemoraSpacing.xs)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.sm), verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tone, size = 48)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Glyph.Chevron, null, Modifier.size(17.dp), tint = colors.muted)
    }
}

@Composable
internal fun RowScope.MoreDestinationCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tone: Int = 0,
    testTag: String,
    onClick: () -> Unit,
) {
    MemoraCard(
        onClick = onClick,
        contentPadding = MemoraSpacing.md,
        modifier = Modifier.weight(1f).heightIn(min = 148.dp).testTag(testTag),
    ) {
        IconBubble(icon, tone, size = 48)
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MemoraTopBar(
    title: String,
    subtitle: String? = null,
    isHome: Boolean = false,
    showBack: Boolean = false,
    showSettings: Boolean = false,
    onBack: () -> Unit = {},
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = MemoraSpacing.xl, vertical = MemoraSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showBack -> IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("back")) {
                Icon(Glyph.Back, s(R.string.back), tint = colors.ink)
            }
            isHome -> Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterStart) { BrandMark(Modifier.size(36.dp)) }
            else -> Spacer(Modifier.size(48.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = if (isHome) MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-1.2).sp)
                    else MaterialTheme.typography.titleLarge,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAbout, modifier = Modifier.size(48.dp).testTag("app_info")) {
            Icon(Glyph.Shield, s(R.string.about), tint = colors.muted)
        }
        if (showSettings) IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).testTag("open_settings")) {
            Icon(Glyph.Settings, s(R.string.settings), tint = colors.muted)
        }
    }
}

@Composable
internal fun MemoraBottomNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar(
        containerColor = colors.card,
        contentColor = colors.ink,
        tonalElevation = 0.dp,
        windowInsets = NavigationBarDefaults.windowInsets,
        modifier = Modifier.border(BorderStroke(1.dp, colors.line)),
    ) {
        val labels = listOf(R.string.nav_home, R.string.nav_secretary, R.string.cases, R.string.nav_memory, R.string.nav_more)
        val icons = listOf(Glyph.Home, Glyph.Chat, Glyph.Folder, Glyph.Memory, Glyph.Grid)
        AppDestination.PRIMARY_TABS.forEachIndexed { index, destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                modifier = Modifier.testTag("nav_${destination.key}"),
                icon = { Icon(icons[index], null, Modifier.size(22.dp)) },
                label = {
                    Text(
                        s(labels[index]),
                        modifier = Modifier.testTag("nav_label_${destination.key}"),
                        fontSize = if (LocalDensity.current.fontScale > 1.15f) 9.5.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        softWrap = true,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onSoftAccent,
                    selectedTextColor = colors.accent,
                    unselectedIconColor = colors.muted,
                    unselectedTextColor = colors.muted,
                    indicatorColor = colors.softAccent,
                ),
            )
        }
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

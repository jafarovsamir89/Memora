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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
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
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted)
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
fun EmptyState(title: String, body: String, icon: ImageVector = Glyph.Search, testTag: String = "empty_state") {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp).testTag(testTag), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBubble(icon, size = 64)
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink,
            modifier = Modifier.semantics { heading() }.testTag("${testTag}_title"))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.testTag("${testTag}_body"))
    }
}

enum class MemoraStatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

@Composable
fun StatusBadge(label: String, tone: MemoraStatusTone = MemoraStatusTone.NEUTRAL, testTag: String = "status_badge") {
    val (background, foreground) = when (tone) {
        MemoraStatusTone.SUCCESS -> colors.softMint to colors.onSoftMint
        MemoraStatusTone.WARNING -> colors.softAmber to colors.amber
        MemoraStatusTone.ERROR -> colors.softError to colors.onSoftError
        MemoraStatusTone.NEUTRAL -> colors.softAccent to colors.onSoftAccent
    }
    Surface(modifier = Modifier.heightIn(min = 32.dp).semantics {
        contentDescription = label
    }.testTag(testTag), color = background, shape = MemoraShapes.pill) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = foreground,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    errorMessage: String? = null,
    testTag: String = "primary_button",
) {
    Column(modifier) {
        Button(onClick = onClick, enabled = enabled && !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag),
            colors = if (errorMessage == null) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = Color.White)) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = if (errorMessage == null) MaterialTheme.colorScheme.onPrimary else Color.White)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        errorMessage?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp).testTag("${testTag}_error")) }
    }
}

@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    errorMessage: String? = null,
    testTag: String = "secondary_button",
) {
    Column(modifier) {
        OutlinedButton(onClick = onClick, enabled = enabled && !loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(testTag)) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        errorMessage?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp).testTag("${testTag}_error")) }
    }
}

@Composable
fun VoiceInputButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    readyStateLabel: String = "Ready",
    activeStateLabel: String = "Active",
    loadingStateLabel: String = "Loading",
    testTag: String = "voice_input_button",
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.03f else 1f,
        animationSpec = tween(MemoraMotion.quickMs),
        label = "voice-button-scale",
    )
    Surface(onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.sizeIn(minWidth = 64.dp, minHeight = 64.dp).semantics {
            role = Role.Button
            contentDescription = label
            stateDescription = when {
                loading -> loadingStateLabel
                active -> activeStateLabel
                else -> readyStateLabel
            }
        }.testTag(testTag), shape = MemoraShapes.pill,
        color = if (active) colors.accent else colors.softAccent,
        contentColor = if (active) colors.onAccent else colors.accent,
        tonalElevation = MemoraElevation.floating) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            else Icon(Glyph.Mic, null, Modifier.size((24f * scale).dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

data class ActionResultPresentation(
    val statusLabel: String,
    val statusTone: MemoraStatusTone,
    val relatedActionLabel: String?,
    val sourceActionLabel: String,
    val correctionActionLabel: String? = null,
    val supportingText: String? = null,
    val warningText: String? = null,
    val detailLines: List<String> = emptyList(),
)

@Composable
fun ActionResultCard(
    presentation: ActionResultPresentation,
    onOpenRelated: (() -> Unit)?,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
    onPrepareCorrection: (() -> Unit)? = null,
    testTag: String = "action_result_card",
) {
    val toneColor = when (presentation.statusTone) {
        MemoraStatusTone.SUCCESS -> colors.green
        MemoraStatusTone.WARNING -> colors.amber
        MemoraStatusTone.ERROR -> colors.error
        MemoraStatusTone.NEUTRAL -> colors.muted
    }
    MemoraCard(tint = if (presentation.statusTone == MemoraStatusTone.SUCCESS) colors.softAccent else colors.card,
        modifier = modifier.testTag(testTag)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.sm)) {
            Icon(if (presentation.statusTone == MemoraStatusTone.SUCCESS) Glyph.Check else Glyph.Folder, null, tint = toneColor)
            Column(Modifier.weight(1f)) {
                Text(s(R.string.action_result_title), style = MaterialTheme.typography.labelSmall, color = colors.muted)
                Text(presentation.statusLabel, style = MaterialTheme.typography.titleSmall, color = toneColor,
                    modifier = Modifier.testTag("action_status_label"))
            }
        }
        presentation.detailLines.forEachIndexed { index, line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = colors.ink,
                modifier = Modifier.testTag("action_detail_$index"))
        }
        presentation.supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
        presentation.warningText?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = colors.amber, modifier = Modifier.testTag("action_delete_not_confirmed")) }
        Column(verticalArrangement = Arrangement.spacedBy(MemoraSpacing.xs)) {
            presentation.relatedActionLabel?.let { label ->
                SecondaryButton(label, { onOpenRelated?.invoke() }, enabled = onOpenRelated != null, testTag = "action_open_related")
            }
            SecondaryButton(presentation.sourceActionLabel, onOpenSource, testTag = "action_open_source")
            presentation.correctionActionLabel?.let { label ->
                SecondaryButton(label, { onPrepareCorrection?.invoke() }, enabled = onPrepareCorrection != null,
                    testTag = "action_prepare_correction")
            }
        }
    }
}

data class PersonCardContent(val name: String, val summary: String? = null, val supportingLabel: String? = null)

@Composable
fun PersonCard(person: PersonCardContent, onClick: () -> Unit, modifier: Modifier = Modifier,
    testTag: String = "person_card") {
    MemoraCard(onClick = onClick, modifier = modifier.heightIn(min = 64.dp).semantics {
        role = Role.Button
        contentDescription = listOfNotNull(person.name, person.supportingLabel, person.summary).joinToString(", ")
    }.testTag(testTag)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(colors.softMint, MemoraShapes.pill), contentAlignment = Alignment.Center) {
                Text(person.name.firstOrNull()?.uppercase() ?: "•", color = colors.onSoftMint,
                    style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MemoraSpacing.xxs)) {
                Text(person.name, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                person.summary?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
                }
                person.supportingLabel?.let { StatusBadge(it, testTag = "${testTag}_status") }
            }
        }
    }
}

data class CaseCardContent(
    val title: String,
    val statusLabel: String,
    val statusTone: MemoraStatusTone,
    val summary: String? = null,
    val nextActionLabel: String? = null,
    val nextAction: String? = null,
)

@Composable
fun CaseCard(case: CaseCardContent, onClick: () -> Unit, modifier: Modifier = Modifier,
    testTag: String = "case_card") {
    MemoraCard(onClick = onClick, modifier = modifier.heightIn(min = 64.dp).semantics {
        role = Role.Button
        contentDescription = listOfNotNull(
            case.title, case.statusLabel, case.summary, case.nextActionLabel, case.nextAction,
        ).joinToString(", ")
    }.testTag(testTag)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(case.title, Modifier.weight(1f), color = colors.ink, style = MaterialTheme.typography.titleMedium)
            StatusBadge(case.statusLabel, case.statusTone, "${testTag}_status")
        }
        case.summary?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted, style = MaterialTheme.typography.bodyMedium) }
        if (case.nextAction != null) {
            Text(listOfNotNull(case.nextActionLabel, case.nextAction).joinToString(": "),
                color = colors.ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

data class TimelineItem(
    val id: String,
    val title: String,
    val timestamp: String,
    val detail: String? = null,
    val excerpt: String? = null,
    val sourceActionLabel: String? = null,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun Timeline(items: List<TimelineItem>, modifier: Modifier = Modifier, testTagPrefix: String = "timeline") {
    val timelineLine = colors.line.copy(alpha = .55f)
    val timelineAccent = colors.accent
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MemoraSpacing.md)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.md), verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .then(if (item.onClick == null) Modifier else Modifier.clickable(role = Role.Button) { item.onClick.invoke() })
                    .testTag("${testTagPrefix}_${item.id}")) {
                Canvas(Modifier.width(16.dp).heightIn(min = 48.dp)) {
                    val centerX = size.width / 2f
                    val dotRadius = 5.dp.toPx()
                    if (index != items.lastIndex) drawLine(timelineLine,
                        Offset(centerX, dotRadius * 2f), Offset(centerX, size.height), 1.dp.toPx())
                    drawCircle(timelineAccent, dotRadius, Offset(centerX, dotRadius + 1.dp.toPx()))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MemoraSpacing.xxs)) {
                    Text(item.title, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
                    Text(item.timestamp, color = colors.muted, style = MaterialTheme.typography.labelSmall)
                    item.detail?.takeIf(String::isNotBlank)?.let { Text(it, color = colors.muted, style = MaterialTheme.typography.bodySmall) }
                    item.excerpt?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("${testTagPrefix}_exact_excerpt_${item.id}")) }
                    item.sourceActionLabel?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier, testTag: String = "loading_state") {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        contentDescription = label
    }.testTag(testTag), horizontalArrangement = Arrangement.spacedBy(MemoraSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.accent)
        Text(label, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorState(title: String, body: String, modifier: Modifier = Modifier,
    retryLabel: String? = null, onRetry: (() -> Unit)? = null, testTag: String = "error_state") {
    Column(modifier.fillMaxWidth().padding(vertical = MemoraSpacing.lg).semantics {
        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive
    }.testTag(testTag), verticalArrangement = Arrangement.spacedBy(MemoraSpacing.sm)) {
        StatusBadge(title, MemoraStatusTone.ERROR, "${testTag}_badge")
        Text(body, color = colors.ink, style = MaterialTheme.typography.bodyMedium)
        if (retryLabel != null && onRetry != null) SecondaryButton(retryLabel, onRetry, testTag = "${testTag}_retry")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationSheet(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    errorMessage: String? = null,
    testTag: String = "confirmation_sheet",
) {
    if (visible) ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag(testTag),
        containerColor = colors.card, contentColor = colors.ink) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = MemoraSpacing.xl, vertical = MemoraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MemoraSpacing.md)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = colors.ink)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            errorMessage?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            PrimaryButton(confirmLabel, onConfirm, enabled = !loading, loading = loading, testTag = "${testTag}_confirm")
            SecondaryButton(dismissLabel, onDismiss, enabled = !loading, testTag = "${testTag}_dismiss")
        }
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

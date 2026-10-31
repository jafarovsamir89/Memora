package ai.memora.app

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.ui.*
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.TaskStoreKey
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoraApp(initialRoute: String = "home", initialTheme: String = "system") {
    var language by rememberSaveable { mutableStateOf("ru") }
    var theme by rememberSaveable { mutableStateOf(initialTheme) }
    val context = LocalContext.current
    val taskStore = remember(context) { EncryptedTaskStore(File(context.filesDir, "tasks-prototype.bin"), TaskStoreKey::get) }
    val systemConfiguration = LocalConfiguration.current
    val resources = remember(language, systemConfiguration, context) {
        val localized = Configuration(systemConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }
        context.createConfigurationContext(localized).resources
    }

    CompositionLocalProvider(LocalStrings provides resources) {
        MemoraTheme(theme) {
            var route by rememberSaveable { mutableStateOf(initialRoute) }
            var parent by rememberSaveable { mutableStateOf("home") }
            var capture by rememberSaveable { mutableStateOf(false) }
            var voice by rememberSaveable { mutableStateOf(false) }
            var draft by rememberSaveable { mutableStateOf("") }
            var drafts by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
            var completed by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
            var detailId by rememberSaveable { mutableStateOf<String?>(null) }
            var about by rememberSaveable { mutableStateOf(false) }
            val snackbar = remember { SnackbarHostState() }
            val screens = rememberSaveableStateHolder()
            val scope = rememberCoroutineScope()
            val navigate: (String) -> Unit = { destination -> parent = route; route = destination }
            val topRoutes = setOf("home", "memory", "tasks", "more")
            val goBack: () -> Unit = { route = if (route in topRoutes) "home" else parent.takeIf { it != route } ?: "home"; parent = "home" }
            BackHandler(enabled = route != "home" && !capture && detailId == null && !about, onBack = goBack)

            val openCapture: (Boolean) -> Unit = { showVoice -> voice = showVoice; capture = true }
            val toggleTask: (String) -> Unit = { id ->
                completed = if (id in completed) completed - id else completed + id
            }
            val routes = listOf("home", "memory", "capture", "tasks", "more")
            val labels = listOf(R.string.nav_home, R.string.nav_memory, R.string.nav_capture, R.string.nav_tasks, R.string.nav_more)
            val icons = listOf(Glyph.Home, Glyph.Memory, Glyph.Mic, Glyph.Tasks, Glyph.Grid)
            val titleId = when (route) {
                "memory" -> R.string.nav_memory; "tasks" -> R.string.nav_tasks; "more" -> R.string.nav_more
                "inbox" -> R.string.inbox; "finance" -> R.string.finance; "people" -> R.string.people
                "projects" -> R.string.projects; "settings" -> R.string.settings; "chat" -> R.string.chat
                else -> R.string.app_name
            }

            Scaffold(
                containerColor = colors.background,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (route !in topRoutes) {
                            IconButton(goBack, Modifier.testTag("back")) { Icon(Glyph.Back, s(R.string.back), tint = colors.ink) }
                        } else BrandMark()
                        Column(Modifier.weight(1f)) {
                            Text(if (route == "home") "memora" else s(titleId),
                                style = if (route == "home") MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-1.2).sp)
                                else MaterialTheme.typography.titleLarge, color = colors.ink)
                            if (route == "home") Text(s(R.string.tagline), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                        TextButton(onClick = { about = true }, modifier = Modifier.testTag("demo_info")) { Pill(s(R.string.demo)) }
                        if (route == "home") IconButton({ navigate("settings") }, Modifier.testTag("open_settings")) {
                            Icon(Glyph.Settings, s(R.string.settings), tint = colors.muted, modifier = Modifier.size(22.dp))
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = colors.card, tonalElevation = 0.dp) {
                        routes.forEachIndexed { index, destination ->
                            val isCapture = destination == "capture"
                            NavigationBarItem(
                                selected = !isCapture && (route == destination || (destination == "more" && route !in topRoutes)),
                                onClick = { if (isCapture) openCapture(true) else { route = destination; parent = "home" } },
                                modifier = Modifier.testTag("nav_$destination"),
                                icon = {
                                    if (isCapture) Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Glyph.Plus, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(25.dp))
                                    } else Icon(icons[index], null, Modifier.size(22.dp))
                                },
                                label = { Text(s(labels[index]), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = colors.accent, selectedTextColor = colors.accent,
                                    unselectedIconColor = colors.muted, unselectedTextColor = colors.muted,
                                    indicatorColor = colors.softAccent,
                                ),
                            )
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                      screens.SaveableStateProvider(route) {
                        when (route) {
                            "home" -> HomeScreen(language, completed, toggleTask, navigate, openCapture, { detailId = it })
                            "memory" -> MemoryScreen({ detailId = it }, { openCapture(false) })
                            "tasks" -> TasksScreen(language, completed, toggleTask, { openCapture(false) }, taskStore)
                            "more" -> MoreScreen(navigate, { about = true })
                            "inbox" -> InboxScreen(drafts, { openCapture(false) })
                            "finance" -> FinanceScreen { detailId = "payment" }
                            "people" -> PeopleScreen { detailId = it }
                            "projects" -> ProjectsScreen { detailId = it }
                            "settings" -> SettingsScreen(theme, { theme = it }, language, { language = it }, { about = true })
                            "chat" -> ChatScreen()
                        }
                      }
                    }
                }
            }

            if (capture) ModalBottomSheet(
                onDismissRequest = { capture = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.background,
            ) {
                val added = s(R.string.draft_added)
                CaptureSheet(voice, { voice = it }, draft, { draft = it }, onSave = {
                    if (draft.isNotBlank()) {
                        drafts = drafts + draft.trim()
                        draft = ""; capture = false
                        if (route != "inbox") { parent = route; route = "inbox" }
                        scope.launch { snackbar.showSnackbar(added) }
                    }
                }, onClose = { capture = false }, language = language)
            }
            val detail = exampleMemories.find { it.id == detailId }
            if (detail != null) ModalBottomSheet(
                onDismissRequest = { detailId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
            ) { MemoryDetail(detail) { detailId = null } }
            if (about) ModalBottomSheet(
                onDismissRequest = { about = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
            ) {
                Column(Modifier.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    BrandMark()
                    Text(s(R.string.demo_title), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                    Text(s(R.string.demo_body), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                    Button({ about = false }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s(R.string.close)) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

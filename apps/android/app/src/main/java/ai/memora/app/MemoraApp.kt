package ai.memora.app

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import ai.memora.app.voice.VoiceFirstScreen
import ai.memora.app.voice.VoiceMemoryScreen
import ai.memora.app.data.EncryptedTaskStore
import ai.memora.app.data.TaskStoreKey
import ai.memora.app.data.AppearanceSettings
import ai.memora.app.data.AppearanceStore
import ai.memora.app.data.AppearanceViewModel
import ai.memora.app.data.CapturePersistence
import ai.memora.app.data.CaptureSubmissionIds
import ai.memora.app.data.CaptureRevisionRequest
import ai.memora.app.data.CaptureViewModel
import ai.memora.app.data.CaptureExport
import ai.memora.app.data.CaptureImportPreview
import ai.memora.app.data.CaptureFileRead
import ai.memora.app.data.CaptureImportReader
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

private data class PendingCaptureImport(val text: String, val preview: CaptureImportPreview.Ready)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoraApp(initialRoute: String = "home", initialTheme: String = "system", capturePersistenceOverride: CapturePersistence? = null,
    appearanceStoreOverride: AppearanceStore? = null, initialLanguage: String = "az", taskStoreOverride: EncryptedTaskStore? = null) {
    val context = LocalContext.current
    val appearanceFactory = remember(context, appearanceStoreOverride, initialTheme, initialLanguage) { AppearanceViewModel.Factory(context, AppearanceSettings(initialLanguage, initialTheme), appearanceStoreOverride) }
    val appearanceModel: AppearanceViewModel = viewModel(factory = appearanceFactory)
    val appearance by appearanceModel.state.collectAsState()
    val language = appearance.settings.language
    val theme = appearance.settings.theme
    val taskStore = remember(context, taskStoreOverride) { taskStoreOverride ?: EncryptedTaskStore(File(context.filesDir, "tasks-prototype.bin"), TaskStoreKey::get) }
    val captureFactory = remember(context, capturePersistenceOverride) { CaptureViewModel.Factory(context, capturePersistenceOverride) }
    val captureModel: CaptureViewModel = viewModel(factory = captureFactory)
    val captureState by captureModel.session.state.collectAsState()
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
            var pendingCommand by rememberSaveable { mutableStateOf<String?>(null) }
            var detailId by rememberSaveable { mutableStateOf<String?>(null) }
            var about by rememberSaveable { mutableStateOf(false) }
            var confirmCaptureExport by remember { mutableStateOf(false) }
            var captureExportError by remember { mutableStateOf(false) }
            var captureImportIssue by remember { mutableStateOf<Int?>(null) }
            var captureImportBusy by remember { mutableStateOf(false) }
            var pendingImport by remember { mutableStateOf<PendingCaptureImport?>(null) }
            val snackbar = remember { SnackbarHostState() }
            val screens = rememberSaveableStateHolder()
            val scope = rememberCoroutineScope()
            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) scope.launch {
                    val result = captureModel.session.export()
                    val success = if (result !is CaptureExport.Ready) false else withContext(Dispatchers.IO) {
                        try {
                            val output = context.contentResolver.openOutputStream(uri) ?: return@withContext false
                            output.use { it.write(result.json.toByteArray(Charsets.UTF_8)); it.flush() }
                            true
                        } catch (_: Exception) { false }
                    }
                    captureExportError = !success
                    if (success) snackbar.showSnackbar(resources.getString(R.string.capture_export_success))
                }
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    captureImportBusy = true
                    captureImportIssue = null
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            try {
                                val input = context.contentResolver.openInputStream(uri) ?: return@withContext CaptureFileRead.Unavailable
                                input.use { CaptureImportReader.read(it) }
                            } catch (_: Exception) { CaptureFileRead.Unavailable }
                        }
                        if (file is CaptureFileRead.Ready) {
                            when (val preview = captureModel.session.previewImport(file.text)) {
                                is CaptureImportPreview.Ready -> pendingImport = PendingCaptureImport(file.text, preview)
                                CaptureImportPreview.Invalid -> captureImportIssue = R.string.capture_import_invalid
                                CaptureImportPreview.Conflict -> captureImportIssue = R.string.capture_import_conflict
                                CaptureImportPreview.LimitReached -> captureImportIssue = R.string.capture_import_limit
                                CaptureImportPreview.Unavailable -> captureImportIssue = R.string.capture_management_error
                            }
                        } else captureImportIssue = when (file) {
                            CaptureFileRead.TooLarge -> R.string.capture_import_limit
                            CaptureFileRead.InvalidEncoding -> R.string.capture_import_invalid
                            else -> R.string.capture_management_error
                        }
                        captureImportBusy = false
                    }
                }
            }
            val navigate: (String) -> Unit = { destination -> parent = route; route = destination }
            val topRoutes = setOf("home", "memory", "tasks", "more")
            val goBack: () -> Unit = { route = if (route in topRoutes) "home" else parent.takeIf { it != route } ?: "home"; parent = "home" }
            val added = s(R.string.draft_added)
            LaunchedEffect(captureState.captures, pendingCommand) {
                val command = pendingCommand
                if (command != null && captureState.captures.any { it.commandId == command }) {
                    pendingCommand = null
                    if (capture) {
                        capture = false
                        if (route != "inbox") { parent = route; route = "inbox" }
                        scope.launch { snackbar.showSnackbar(added) }
                    }
                }
            }
            BackHandler(enabled = route != "home" && !capture && detailId == null && !about, onBack = goBack)

            val openCapture: (Boolean) -> Unit = { showVoice ->
                voice = showVoice; capture = true
            }
            val routes = listOf("home", "memory", "capture", "tasks", "more")
            val labels = listOf(R.string.nav_home, R.string.nav_memory, R.string.nav_capture, R.string.nav_tasks, R.string.nav_more)
            val icons = listOf(Glyph.Home, Glyph.Memory, Glyph.Mic, Glyph.Tasks, Glyph.Grid)
            val titleId = when (route) {
                "memory" -> R.string.nav_memory; "tasks" -> R.string.nav_tasks; "more" -> R.string.nav_more
                "inbox" -> R.string.inbox; "finance" -> R.string.finance; "people" -> R.string.people
                "projects" -> R.string.projects; "settings" -> R.string.settings; "chat" -> R.string.chat
                "voice_memory" -> R.string.voice_memory_title
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
                        IconButton(onClick = { about = true }, modifier = Modifier.testTag("app_info")) { Icon(Glyph.Shield, s(R.string.about)) }
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
                            "home" -> WorkingHomeScreen(language, captureState, navigate, openCapture, { detailId = it }) {
                                VoiceFirstScreen()
                            }
                            "memory" -> WorkingMemoryScreen(captureState, { detailId = it }, { openCapture(false) }, { navigate("inbox") })
                            "tasks" -> ScreenColumn { ScreenHeading(s(R.string.tasks_title), s(R.string.working_tasks_hint)); TaskPrototype(taskStore) }
                            "more" -> MoreScreen(navigate, { about = true })
                            "voice_memory" -> VoiceMemoryScreen()
                            "inbox" -> InboxScreen(captureState.captures, captureState.loading, captureState.error && !captureState.ready,
                                captureState.managementError || captureExportError,
                                captureState.ready && (!captureState.error || captureState.limitReached) && !captureState.dirty && !captureState.saving &&
                                    !captureState.submitting && !captureState.retrySubmission && !captureState.managementBusy && !captureImportBusy,
                                { captureModel.session.retryLoad() }, { openCapture(false) },
                                { captureExportError = false; confirmCaptureExport = true },
                                { captureImportIssue = null; importLauncher.launch(arrayOf("application/json", "text/plain")) },
                                captureImportIssue?.let { resources.getString(it) }, captureState.importDone, captureState.importStale,
                                { entries, archived -> captureModel.session.archive(entries, archived) },
                                captureState.managementBusy, { request -> captureModel.session.revise(request) },
                                { entry -> captureModel.session.delete(entry.source.id, entry.source.revisions.last().id) })
                            "finance" -> UnavailableModuleScreen(s(R.string.finance))
                            "people" -> UnavailableModuleScreen(s(R.string.people))
                            "projects" -> UnavailableModuleScreen(s(R.string.projects))
                            "settings" -> SettingsScreen(theme, { appearanceModel.change(theme = it) }, language, { appearanceModel.change(language = it) }, appearance.ready && !appearance.busy, appearance.error, { appearanceModel.load() }, { about = true })
                            "chat" -> ChatScreen(language, memories = captureState.captures, onSource = { detailId = it },
                                memoriesReady = captureState.ready, onRecordNote = { voice = true; capture = true }, onRemember = { text ->
                                if (captureState.ready && !captureState.submitting && !captureState.retrySubmission &&
                                    !captureState.managementBusy && !captureState.dirty && !captureState.saving &&
                                    captureState.draft.isBlank() && ai.memora.app.domain.TextSourceRules.validDraft(text)) {
                                    captureModel.session.edit(text); openCapture(false); true
                                } else false
                            })
                        }
                      }
                    }
                }
            }

            if (capture) ModalBottomSheet(
                onDismissRequest = {
                    if (!captureState.dirty && !captureState.saving && !captureState.submitting && !captureState.retrySubmission)
                        capture = false
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.background,
            ) {
                val submit: () -> Unit = {
                    val command = if (captureState.retrySubmission) pendingCommand ?: UUID.randomUUID().toString()
                        else UUID.randomUUID().toString()
                    pendingCommand = command
                    captureModel.session.submit(CaptureSubmissionIds(command, UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), Instant.now(), ZoneId.systemDefault().id))
                }
                CaptureSheet(voice, { voice = it }, captureState, { captureModel.session.edit(it) }, onSave = submit,
                    onRetry = {
                        when {
                            !captureState.ready -> captureModel.session.retryLoad()
                            captureState.retrySubmission -> submit()
                            else -> captureModel.session.retrySave()
                        }
                    }, onClose = { capture = false }, language = language)
            }
            if (confirmCaptureExport) AlertDialog(onDismissRequest = { confirmCaptureExport = false },
                title = { Text(s(R.string.capture_export)) }, text = { Text(s(R.string.capture_export_warning)) },
                confirmButton = { TextButton(onClick = { confirmCaptureExport = false; exportLauncher.launch("memora-captures.json") },
                    modifier = Modifier.testTag("capture_export_confirm")) { Text(s(R.string.capture_export)) } },
                dismissButton = { TextButton(onClick = { confirmCaptureExport = false }) { Text(s(R.string.task_cancel)) } })
            pendingImport?.let { pending ->
                CaptureImportDialog(pending.preview, onConfirm = {
                        pendingImport = null
                        captureModel.session.applyImport(pending.text, pending.preview.ticket)
                    }, onCancel = { pendingImport = null })
            }
            val detail = captureState.captures.find { it.source.id == detailId }
            if (detail != null) ModalBottomSheet(
                onDismissRequest = { detailId = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
            ) { StoredMemoryDetail(detail) { detailId = null } }
            if (about) ModalBottomSheet(
                onDismissRequest = { about = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background,
            ) {
                Column(Modifier.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    BrandMark()
                    Text(s(R.string.about), style = MaterialTheme.typography.headlineMedium, color = colors.ink)
                    Text(s(R.string.working_about), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                    Button({ about = false }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s(R.string.close)) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

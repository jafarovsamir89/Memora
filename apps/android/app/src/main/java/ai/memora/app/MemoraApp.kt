package ai.memora.app

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.memora.app.ui.*
import ai.memora.app.voice.VoiceMemoryScreen
import ai.memora.app.data.protectedstore.DomainKind
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
import ai.memora.app.data.LocalProfileState
import ai.memora.app.data.LocalProfileViewModel
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
    appearanceStoreOverride: AppearanceStore? = null, initialLanguage: String = "az", taskStoreOverride: EncryptedTaskStore? = null,
    textTurnOverride: (suspend (String) -> ai.memora.app.voice.VoiceFirstResult)? = null,
    textTurnProgressOverride: (suspend (String, (ai.memora.app.voice.VoiceTurnProgress) -> Unit) -> ai.memora.app.voice.VoiceFirstResult)? = null) {
    val context = LocalContext.current
    val appearanceFactory = remember(context, appearanceStoreOverride, initialTheme, initialLanguage) { AppearanceViewModel.Factory(context, AppearanceSettings(initialLanguage, initialTheme), appearanceStoreOverride) }
    val appearanceModel: AppearanceViewModel = viewModel(factory = appearanceFactory)
    val appearance by appearanceModel.state.collectAsState()
    val language = appearance.settings.language
    val theme = appearance.settings.theme
    val profileStoreOverride = LocalProfileStoreOverride.current
    val profileFactory = remember(context, profileStoreOverride, initialLanguage) {
        LocalProfileViewModel.Factory(context, initialLanguage, profileStoreOverride)
    }
    val profileModel: LocalProfileViewModel = viewModel(factory = profileFactory)
    val profileState by profileModel.state.collectAsState()
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
          when (val profile = profileState) {
            LocalProfileState.Loading -> LocalProfileLoadingScreen()
            LocalProfileState.Onboarding -> LocalProfileOnboardingScreen(
                initialLanguage = language,
                canContinue = appearance.ready,
                onLanguageChange = { appearanceModel.change(language = it) },
                saving = false,
                saveError = false,
                onStart = profileModel::complete,
            )
            is LocalProfileState.Failed -> {
                if (profile.pendingInput != null) {
                    LocalProfileOnboardingScreen(
                        initialLanguage = profile.pendingInput.language,
                        initialInput = profile.pendingInput,
                        canContinue = appearance.ready,
                        onLanguageChange = { appearanceModel.change(language = it) },
                        saving = false,
                        saveError = true,
                        onStart = profileModel::complete,
                    )
                } else LocalProfileErrorScreen(R.string.onboarding_load_error, profileModel::retry)
            }
            is LocalProfileState.Ready -> {
            var navigation by rememberSaveable(stateSaver = AppNavigationState.Saver) {
                mutableStateOf(AppNavigationState.start(AppRoute.fromLegacyRoute(initialRoute)))
            }
            val route = navigation.current
            val navigateRoute: (AppRoute) -> Unit = { destination ->
                navigation = navigation.navigate(destination)
            }
            val navigate: (String) -> Unit = { destination ->
                navigateRoute(AppRoute.fromLegacyRoute(destination))
            }
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
            val goBack: () -> Unit = {
                navigation = navigation.back()
            }
            val added = s(R.string.draft_added)
            LaunchedEffect(captureState.captures, pendingCommand) {
                val command = pendingCommand
                if (command != null && captureState.captures.any { it.commandId == command }) {
                    pendingCommand = null
                    if (capture) {
                        capture = false
                        if (route.destination != AppDestination.INBOX) navigate("inbox")
                        scope.launch { snackbar.showSnackbar(added) }
                    }
                }
            }
            BackHandler(enabled = (route.destination != AppDestination.TODAY || navigation.backStack.isNotEmpty()) &&
                !capture && detailId == null && !about, onBack = goBack)

            val openCapture: (Boolean) -> Unit = { showVoice ->
                voice = showVoice; capture = true
            }
            val titleId = when (route.destination) {
                AppDestination.MEMORY -> R.string.nav_memory
                AppDestination.TASKS -> R.string.nav_tasks
                AppDestination.MORE -> R.string.nav_more
                AppDestination.CASES -> R.string.cases
                AppDestination.INBOX -> R.string.inbox
                AppDestination.NOTES -> R.string.notes_title
                AppDestination.FINANCE -> R.string.finance
                AppDestination.CALENDAR -> R.string.calendar_title
                AppDestination.PEOPLE -> R.string.people
                AppDestination.PROJECTS -> R.string.projects
                AppDestination.SETTINGS -> R.string.settings
                AppDestination.SECRETARY -> R.string.nav_secretary
                AppDestination.VOICE_MEMORY -> R.string.voice_memory_title
                AppDestination.TABLES -> R.string.tables_title
                AppDestination.PORTABLE_BACKUP -> R.string.backup_title
                AppDestination.LICENSES -> R.string.open_source_licenses
                AppDestination.ACTION_TARGET -> when (route.targetKind) {
                    AppTargetKind.PERSON -> R.string.people
                    AppTargetKind.CASE -> R.string.cases
                    AppTargetKind.FINANCE -> R.string.finance
                    AppTargetKind.TASK -> R.string.nav_tasks
                    AppTargetKind.CALENDAR -> R.string.calendar_title
                    AppTargetKind.SOURCE -> R.string.entity_source_turn
                    else -> R.string.action_related_details
                }
                AppDestination.TODAY -> R.string.app_name
            }

            Scaffold(
                containerColor = colors.background,
                topBar = {
                    MemoraTopBar(
                        title = if (route.destination == AppDestination.TODAY) "memora" else s(titleId),
                        subtitle = if (route.destination == AppDestination.TODAY) s(R.string.tagline) else null,
                        isHome = route.destination == AppDestination.TODAY,
                        showBack = !route.destination.isPrimaryTab || navigation.backStack.isNotEmpty(),
                        showSettings = route.destination == AppDestination.TODAY,
                        onBack = goBack,
                        onAbout = { about = true },
                        onSettings = { navigate("settings") },
                    )
                },
                bottomBar = {
                    MemoraBottomNavigation(
                        selected = route.selectedPrimaryTab,
                        onSelect = { navigation = navigation.selectTab(it) },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                      screens.SaveableStateProvider(route.stateKey) {
                        when (route.destination) {
                            AppDestination.TODAY -> WorkingHomeScreen(
                                language, captureState, navigate, { detailId = it }, taskStore, onOpenTarget = navigateRoute,
                            )
                            AppDestination.MEMORY -> WorkingMemoryScreen(captureState, { detailId = it }, { openCapture(false) }, { navigate("inbox") },
                                localProfile = profile.profile, updateLocalProfile = { input, done ->
                                    profileModel.updateProfile(input) { saved ->
                                        if (saved) appearanceModel.change(language = input.language)
                                        done(saved)
                                    }
                                }, onOpenTarget = navigateRoute)
                            AppDestination.TASKS -> TaskPrototype(taskStore, onOpenTarget = navigateRoute)
                            AppDestination.MORE -> MoreScreen(navigate, { about = true }, { openCapture(true) }, language)
                            AppDestination.VOICE_MEMORY -> VoiceMemoryScreen()
                            AppDestination.NOTES -> ProjectNotesScreen(DomainKind.NOTE, navigateRoute)
                            AppDestination.TABLES -> TablesScreen()
                            AppDestination.PORTABLE_BACKUP -> PortableBackupScreen()
                            AppDestination.LICENSES -> OpenSourceLicensesScreen()
                            AppDestination.INBOX -> InboxScreen(captureState.captures, captureState.loading, captureState.error && !captureState.ready,
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
                            AppDestination.FINANCE -> FinanceScreen(onOpenTarget = navigateRoute, language = language)
                            AppDestination.CALENDAR -> CalendarScreen(onOpenTarget = navigateRoute)
                            AppDestination.PEOPLE -> SavedObjectsScreen(DomainKind.PERSON, navigateRoute)
                            AppDestination.CASES -> CasesScreen(onOpenSecretary = { navigate("chat") }, onOpenTarget = navigateRoute)
                            AppDestination.PROJECTS -> ProjectNotesScreen(DomainKind.PROJECT, navigateRoute)
                            AppDestination.SETTINGS -> SettingsScreen(theme, { appearanceModel.change(theme = it) }, language, {
                                appearanceModel.change(language = it)
                                (profileState as? LocalProfileState.Ready)?.profile?.let { profile -> profileModel.syncLanguage(it) }
                            }, appearance.ready && !appearance.busy, appearance.error, { appearanceModel.load() }, { about = true })
                            AppDestination.SECRETARY -> AgentChatScreen(language, showVoiceControl = true, onOpenArea = navigate,
                                onOpenTarget = navigateRoute,
                                textTurnOverride = textTurnOverride, textTurnProgressOverride = textTurnProgressOverride)
                            AppDestination.ACTION_TARGET -> ActionTargetDetailScreen(route, navigateRoute)
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
                    OutlinedButton({ about = false; navigate("licenses") }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_source_licenses")) {
                        Text(s(R.string.open_source_licenses))
                    }
                    Button({ about = false }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s(R.string.close)) }
                    Spacer(Modifier.height(16.dp))
                }
            }
            }
          }
        }
    }
}

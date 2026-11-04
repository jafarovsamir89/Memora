package ai.memora.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.memora.app.data.protectedstore.AgentDatabase
import ai.memora.app.data.protectedstore.PortableBackupCipher
import ai.memora.app.data.protectedstore.PortableBackupExporter
import ai.memora.app.data.protectedstore.PortableBackupFileCandidate
import ai.memora.app.data.protectedstore.PortableBackupImporter
import ai.memora.app.data.protectedstore.PortableBackupValidator
import ai.memora.app.data.protectedstore.PortableBackupCutoverException
import ai.memora.app.ui.MemoraCard
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.ScreenColumn
import ai.memora.app.ui.ScreenHeading
import ai.memora.app.ui.colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.Instant

@Composable
internal fun PortableBackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<Int?>(null) }
    var success by remember { mutableStateOf<Int?>(null) }
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }
    var candidate by remember { mutableStateOf<PortableBackupFileCandidate?>(null) }
    var verifiedMetadata by remember { mutableStateOf<BackupPreview?>(null) }
    var confirmReplacement by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { PortableBackupImporter.cleanupAbandonedCandidates(context.applicationContext) }
    }

    DisposableEffect(candidate) {
        onDispose { candidate?.close() }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val secret = pendingExportPassword
        pendingExportPassword = null
        if (uri == null) {
            secret?.fill('\u0000')
        } else if (secret != null) {
            busy = true
            issue = null
            success = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val database = AgentDatabase.open(context.applicationContext)
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw FileNotFoundException("Could not open backup destination")
                        output.use {
                            PortableBackupExporter.export(
                                context.applicationContext,
                                database.openHelper.writableDatabase,
                                secret,
                                it,
                            )
                        }
                        true
                    } catch (_: Exception) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        false
                    } finally {
                        secret.fill('\u0000')
                    }
                }
                busy = false
                if (result) success = R.string.backup_export_success else issue = R.string.backup_export_failed
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val secret = password.toCharArray()
        password = ""
        busy = true
        issue = null
        success = null
        scope.launch {
            var imported: PortableBackupFileCandidate? = null
            try {
                val validated = withContext(Dispatchers.IO) {
                    val length = declaredDocumentSize(context, uri)
                        ?: throw BackupFileSizeException()
                    if (length !in 1..PortableBackupCipher.MAX_CONTAINER_BYTES) throw BackupFileSizeException()
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException("Could not open backup source")
                    imported = stream.use {
                        PortableBackupImporter.prepare(context.applicationContext, it, length, secret)
                    }
                    PortableBackupValidator.validate(context.applicationContext, imported!!)
                }
                candidate?.close()
                candidate = imported
                imported = null
                success = R.string.backup_import_ready
                issue = null
                // Store only the non-sensitive verified metadata in this screen; contents stay encrypted.
                verifiedMetadata = BackupPreview(
                    candidate!!.manifest.roomSchemaVersion,
                    candidate!!.manifest.createdAtEpochMillis,
                    candidate!!.manifest.encryptedSnapshotBytes.toLong(),
                    validated.tableCount,
                )
            } catch (_: BackupFileSizeException) {
                issue = R.string.backup_import_size
            } catch (_: Exception) {
                issue = R.string.backup_wrong_password
            } finally {
                secret.fill('\u0000')
                imported?.close()
                busy = false
            }
        }
    }

    ScreenColumn {
        ScreenHeading(s(R.string.backup_title), s(R.string.backup_subtitle))
        MemoraCard {
            Text(s(R.string.backup_encrypted_note), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(1024) },
                modifier = Modifier.fillMaxWidth().testTag("portable_backup_password"),
                label = { Text(s(R.string.backup_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
            )
            Text(s(R.string.backup_password_requirements), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            OutlinedTextField(
                value = repeatedPassword,
                onValueChange = { repeatedPassword = it.take(1024) },
                modifier = Modifier.fillMaxWidth().testTag("portable_backup_repeat_password"),
                label = { Text(s(R.string.backup_confirm_passphrase)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedButton(
                onClick = {
                    if (password.length < 12) issue = R.string.backup_passphrase_short
                    else if (password != repeatedPassword) issue = R.string.backup_passphrase_mismatch
                    else {
                        pendingExportPassword = password.toCharArray()
                        password = ""
                        repeatedPassword = ""
                        exportLauncher.launch("Memora-backup.memora")
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("portable_backup_export"),
            ) { Text(s(R.string.backup_export)) }
            OutlinedButton(
                onClick = {
                    if (password.length < 12) issue = R.string.backup_passphrase_short
                    else importLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("portable_backup_import"),
            ) { Text(s(R.string.backup_import)) }
            if (busy) CircularProgressIndicator()
            issue?.let { Text(s(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("portable_backup_error")) }
            success?.let { Text(s(it), color = colors.green, modifier = Modifier.testTag("portable_backup_status")) }
            if (candidate != null) {
                val preview = verifiedMetadata
                if (preview != null) {
                    Text(
                        s(R.string.backup_metadata, preview.schema, Instant.ofEpochMilli(preview.createdAt).toString(), preview.tableCount, preview.snapshotBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(s(R.string.backup_import_ready), color = colors.ink)
                    OutlinedButton(
                        onClick = { confirmReplacement = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("portable_backup_replace"),
                    ) { Text(s(R.string.backup_restore)) }
                }
            }
        }
    }

    if (confirmReplacement && candidate != null) {
        AlertDialog(
            onDismissRequest = { confirmReplacement = false },
            title = { Text(s(R.string.backup_restore)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s(R.string.backup_replace_warning))
                verifiedMetadata?.let { Text(s(R.string.backup_metadata, it.schema, Instant.ofEpochMilli(it.createdAt).toString(), it.tableCount, it.snapshotBytes)) }
            } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplacement = false
                        busy = true
                        issue = null
                        val selected = candidate
                        scope.launch {
                            val armResult = withContext(Dispatchers.IO) {
                                runCatching {
                                    AgentDatabase.armPortableRestore(context.applicationContext, selected!!, explicitUserConfirmation = true)
                                }
                            }
                            selected?.close()
                            candidate = null
                            val restartRequired = armResult.isSuccess ||
                                (armResult.exceptionOrNull() as? PortableBackupCutoverException)?.restartRequired == true
                            if (restartRequired && restartMemora(context)) {
                                // Process exits immediately after starting a clean app task.
                            } else if (restartRequired) {
                                busy = false
                                issue = R.string.backup_restart_failed
                            } else {
                                busy = false
                                issue = R.string.backup_import_failed
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("portable_backup_confirm_replace"),
                ) { Text(s(R.string.backup_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmReplacement = false }) { Text(s(R.string.task_cancel)) } },
        )
    }
}

@Composable
private fun s(id: Int): String = LocalStrings.current.getString(id)

@Composable
private fun s(id: Int, vararg args: Any): String = LocalStrings.current.getString(id, *args)

private data class BackupPreview(val schema: Int, val createdAt: Long, val snapshotBytes: Long, val tableCount: Int)
private class BackupFileSizeException : IllegalArgumentException()

private fun declaredDocumentSize(context: Context, uri: Uri): Long? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
    }

private fun restartMemora(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?: return false
    context.startActivity(intent)
    Process.killProcess(Process.myPid())
    return true
}

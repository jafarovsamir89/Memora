package ai.memora.app.voice

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ai.memora.app.R
import ai.memora.app.data.*
import ai.memora.app.ui.*
import java.io.File

/** Shows the evidence behind saved transfers; the owner can remove an incorrect entry. */
@Composable
fun VoiceMemoryScreen() {
    val context = LocalContext.current
    val store = remember { VoiceMoneyStore(File(context.filesDir, "voice-memory.bin"), VoiceMoneyKey::get) }
    var result by remember { mutableStateOf(store.load()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var confirmExport by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val json = store.exportJson()
            exportError = json == null || !runCatching {
                val output = context.contentResolver.openOutputStream(uri) ?: error("unavailable")
                output.use { it.write(json!!.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
        }
    }
    val read = result
    ScreenColumn {
        ScreenHeading(s(R.string.voice_memory_title), s(R.string.voice_memory_help))
        when (read) {
            VoiceStoreRead.Unavailable -> Text(s(R.string.capture_storage_error))
            is VoiceStoreRead.Ready -> {
                OutlinedButton(onClick = { confirmExport = true }) { Text(s(R.string.voice_memory_export)) }
                if (exportError) Text(s(R.string.capture_management_error))
                if (read.snapshot.receipts.isEmpty()) Text(s(R.string.working_no_data))
                read.snapshot.receipts.asReversed().forEach { receipt ->
                    val name = read.snapshot.persons.firstOrNull { it.id == receipt.personId }?.name.orEmpty()
                    MemoraCard {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text("${receipt.minorUnits / 100},${(receipt.minorUnits % 100).toString().padStart(2, '0')} AZN")
                        Text(receipt.source)
                        TextButton(onClick = { pending = receipt.id }) { Text(s(R.string.task_delete)) }
                    }
                }
            }
        }
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false },
        title = { Text(s(R.string.voice_memory_export)) }, text = { Text(s(R.string.voice_memory_export_warning)) },
        confirmButton = { TextButton(onClick = { confirmExport = false; exportError = false; export.launch("memora-voice-memory.json") }) { Text(s(R.string.voice_memory_export)) } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(s(R.string.task_cancel)) } })
    if (pending != null) AlertDialog(onDismissRequest = { pending = null },
        title = { Text(s(R.string.voice_memory_delete_title)) },
        text = { Text(s(R.string.voice_memory_delete_body)) },
        confirmButton = { TextButton(onClick = {
            if (store.deleteReceipt(pending!!)) result = store.load() else result = VoiceStoreRead.Unavailable
            pending = null
        }) { Text(s(R.string.task_delete)) } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text(s(R.string.task_cancel)) } })
}

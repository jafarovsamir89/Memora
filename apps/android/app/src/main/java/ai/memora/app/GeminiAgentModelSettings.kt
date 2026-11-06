package ai.memora.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.memora.app.ai.GeminiAgentModel
import ai.memora.app.data.GeminiAgentModelSelectionStore
import ai.memora.app.ui.LocalStrings
import ai.memora.app.ui.SectionHeading
import ai.memora.app.ui.colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GeminiAgentModelSettings() {
    val context = LocalContext.current
    val store = remember(context) { GeminiAgentModelSelectionStore(context) }
    val scope = rememberCoroutineScope()
    var selectedModel by remember(context) { mutableStateOf(store.selectedModel()) }
    var saveFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    SectionHeading(LocalStrings.current.getString(R.string.ai_model_settings_title))
    Text(LocalStrings.current.getString(R.string.ai_model_settings_description), color = colors.muted,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_model_settings_description"))
    Text(LocalStrings.current.getString(R.string.ai_model_limits_note), color = colors.muted,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_model_limits_note"))
    listOf(
        GeminiAgentModel.FLASH_LITE_35 to (R.string.ai_model_35_title to R.string.ai_model_35_description),
        GeminiAgentModel.FLASH_LITE_31 to (R.string.ai_model_31_title to R.string.ai_model_31_description),
        GeminiAgentModel.FLASH_36 to (R.string.ai_model_36_title to R.string.ai_model_36_description),
    ).forEach { (model, labels) ->
        key(model.modelId) { Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(
                    enabled = !saving,
                    role = Role.RadioButton,
                    onClick = {
                        if (!saving) {
                            val previousModel = selectedModel
                            selectedModel = model
                            saving = true
                            saveFailed = false
                            scope.launch(Dispatchers.IO) {
                                val saved = runCatching { store.save(model) }.getOrDefault(false)
                                withContext(Dispatchers.Main) {
                                    if (!saved) selectedModel = previousModel
                                    saveFailed = !saved
                                    saving = false
                                }
                            }
                        }
                    },
                )
                .semantics { selected = selectedModel == model }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("ai_model_${model.modelId.replace('.', '_').replace('-', '_')}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selectedModel == model, onClick = null, enabled = !saving)
            Column(Modifier.padding(start = 4.dp)) {
                Text(LocalStrings.current.getString(labels.first), color = colors.ink, style = MaterialTheme.typography.titleSmall)
                Text(LocalStrings.current.getString(labels.second), color = colors.muted, style = MaterialTheme.typography.bodySmall)
            }
        } }
    }
    if (saving) Text(LocalStrings.current.getString(R.string.ai_model_saving), color = colors.muted,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_model_saving"))
    if (saveFailed) Text(LocalStrings.current.getString(R.string.ai_model_save_failed), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai_model_save_failed"))
}

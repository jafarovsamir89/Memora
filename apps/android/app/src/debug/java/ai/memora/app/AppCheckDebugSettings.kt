package ai.memora.app

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import ai.memora.app.ai.AppCheckDebugFailure
import ai.memora.app.ai.DEBUG_APP_CHECK_PREFS
import ai.memora.app.ai.DEBUG_APP_CHECK_TOKEN_KEY
import ai.memora.app.ui.*
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck

@Composable
fun AppCheckDebugSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(DEBUG_APP_CHECK_PREFS, 0) }
    var token by remember { mutableStateOf("") }
    var configured by remember(context) { mutableStateOf(preferences.contains(DEBUG_APP_CHECK_TOKEN_KEY)) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<Int?>(null) }
    var httpStatus by rememberSaveable { mutableStateOf<Int?>(null) }

    SectionHeading(s(R.string.app_check_debug_title))
    MemoraCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
            Text(s(R.string.app_check_debug_description))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.take(2048); status = null; httpStatus = null },
                modifier = Modifier.fillMaxWidth().testTag("app_check_debug_token"),
                label = { Text(s(R.string.app_check_debug_token_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                onClick = {
                    val value = token.trim()
                    if (value.isEmpty()) {
                        status = R.string.app_check_debug_token_empty
                        return@Button
                    }
                    if (FirebaseApp.getApps(context).isEmpty()) {
                        status = R.string.app_check_debug_not_configured
                        return@Button
                    }
                    if (!preferences.edit().putString(DEBUG_APP_CHECK_TOKEN_KEY, value).commit()) {
                        status = R.string.app_check_debug_save_failed
                        return@Button
                    }
                    configured = true
                    token = ""
                    busy = true
                    status = null
                    httpStatus = null
                    FirebaseAppCheck.getInstance(FirebaseApp.getInstance()).getAppCheckToken(true)
                        .addOnCompleteListener { task ->
                            busy = false
                            if (task.isSuccessful) {
                                status = R.string.app_check_debug_token_accepted
                            } else {
                                val failure = task.exception as? AppCheckDebugFailure
                                httpStatus = failure?.httpStatus
                                status = if (httpStatus == null) R.string.app_check_debug_exchange_failed
                                    else R.string.app_check_debug_http_failed
                            }
                        }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("app_check_debug_save"),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(s(R.string.app_check_debug_save))
            }
            if (configured) {
                Text(s(R.string.app_check_debug_token_saved))
                OutlinedButton(
                    onClick = {
                        val removed = preferences.edit().remove(DEBUG_APP_CHECK_TOKEN_KEY).commit()
                        configured = !removed
                        status = if (removed) null else R.string.app_check_debug_save_failed
                        httpStatus = null
                        token = ""
                        if (removed && FirebaseApp.getApps(context).isNotEmpty()) {
                            busy = true
                            FirebaseAppCheck.getInstance(FirebaseApp.getInstance()).getAppCheckToken(true)
                                .addOnCompleteListener { task ->
                                    busy = false
                                    if (task.isSuccessful) status = R.string.app_check_debug_token_removed
                                    else {
                                        val failure = task.exception as? AppCheckDebugFailure
                                        httpStatus = failure?.httpStatus
                                        status = if (httpStatus == null) R.string.app_check_debug_exchange_failed
                                            else R.string.app_check_debug_http_failed
                                    }
                                }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("app_check_debug_clear"),
                ) { Text(s(R.string.app_check_debug_clear)) }
            }
            status?.let { message ->
                Text(
                    text = if (message == R.string.app_check_debug_http_failed) {
                        context.getString(message, httpStatus ?: 0)
                    } else s(message),
                    color = if (message == R.string.app_check_debug_token_accepted || message == R.string.app_check_debug_token_removed)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("app_check_debug_status"),
                )
            }
        }
    }
}

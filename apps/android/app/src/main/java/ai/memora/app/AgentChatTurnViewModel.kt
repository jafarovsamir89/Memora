package ai.memora.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.memora.app.voice.AgentVoiceTurnProcessor
import ai.memora.app.voice.VoiceFirstResult
import ai.memora.app.voice.VoiceTurnProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class AgentChatTurnState(
    val busy: Boolean = false,
    val pendingMessage: String = "",
    val turnId: String? = null,
    val actions: List<ai.memora.app.voice.VoiceTurnActionResult> = emptyList(),
    val stage: String = "thinking",
    val failureCode: String? = null,
    val failedDraft: String? = null,
    val failureVersion: Int = 0,
    val completionVersion: Int = 0,
)

/** Holds an in-flight secretary turn across route changes and Activity recreation. */
internal class AgentChatTurnViewModel(
    private val processor: AgentVoiceTurnProcessor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AgentChatTurnState())
    val state: StateFlow<AgentChatTurnState> = mutableState.asStateFlow()

    fun sendText(
        text: String,
        executor: suspend ((VoiceTurnProgress) -> Unit) -> VoiceFirstResult = { progress ->
            processor.handleText(text, progress)
        },
    ) {
        if (text.isBlank() || mutableState.value.busy) return
        viewModelScope.launch { runTurn(text, restoreDraftOnFailure = true, executor) }
    }

    suspend fun handleVoice(
        text: String,
        onProgress: (VoiceTurnProgress) -> Unit,
        executor: suspend (String, (VoiceTurnProgress) -> Unit) -> VoiceFirstResult = { transcript, progress ->
            processor.handle(transcript, progress)
        },
    ): VoiceFirstResult {
        val completion = CompletableDeferred<VoiceFirstResult>()
        val request: Deferred<Unit> = viewModelScope.async {
            try {
                val result = runTurn(text, restoreDraftOnFailure = true) { acceptProgress ->
                    executor(text) { progress ->
                        acceptProgress(progress)
                        onProgress(progress)
                    }
                }
                completion.complete(result)
            } catch (cancelled: CancellationException) {
                completion.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                completion.complete(VoiceFirstResult.Unavailable)
            }
        }
        // The waiter can be cancelled with the voice screen; the Activity-scoped request keeps running.
        request.invokeOnCompletion { error ->
            if (error != null && !completion.isCompleted) completion.complete(VoiceFirstResult.Unavailable)
        }
        return completion.await()
    }

    fun acceptProgress(progress: VoiceTurnProgress) {
        mutableState.update { current -> when (progress) {
            is VoiceTurnProgress.Stage -> current.copy(stage = progress.key)
            is VoiceTurnProgress.ConfirmedActions -> current.copy(
                turnId = progress.turnId,
                actions = progress.results,
            )
        } }
    }

    fun clearFailure() {
        mutableState.update { it.copy(failureCode = null, failedDraft = null) }
    }

    private suspend fun runTurn(
        text: String,
        restoreDraftOnFailure: Boolean,
        executor: suspend ((VoiceTurnProgress) -> Unit) -> VoiceFirstResult,
    ): VoiceFirstResult {
        val start = mutableState.value
        if (start.busy) return VoiceFirstResult.Unavailable
        mutableState.update { it.copy(
            busy = true,
            pendingMessage = text,
            turnId = null,
            actions = emptyList(),
            stage = "thinking",
            failureCode = null,
            failedDraft = null,
        ) }
        var result: VoiceFirstResult = VoiceFirstResult.Unavailable
        var failedCode: String? = null
        try {
            result = withTimeout(65_000) { executor(::acceptProgress) }
            if (result is VoiceFirstResult.Failed) failedCode = result.code
            if (result == VoiceFirstResult.Unavailable) failedCode = "request_failed"
        } catch (_: TimeoutCancellationException) {
            failedCode = "request_timeout"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failedCode = "request_failed"
        } finally {
            mutableState.update { current -> current.copy(
                busy = false,
                pendingMessage = if (failedCode != null && current.actions.isNotEmpty()) current.pendingMessage else "",
                turnId = if (failedCode != null && current.actions.isNotEmpty()) current.turnId else null,
                actions = if (failedCode != null && current.actions.isNotEmpty()) current.actions else emptyList(),
                failureCode = failedCode,
                failedDraft = text.takeIf { failedCode != null && restoreDraftOnFailure && current.actions.isEmpty() },
                failureVersion = current.failureVersion + if (failedCode != null) 1 else 0,
                completionVersion = current.completionVersion + if (result is VoiceFirstResult.Ready) 1 else 0,
            ) }
        }
        return if (failedCode == null) result else VoiceFirstResult.Failed(failedCode!!)
    }

    class Factory(private val processor: AgentVoiceTurnProcessor) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AgentChatTurnViewModel::class.java))
            return AgentChatTurnViewModel(processor) as T
        }
    }
}

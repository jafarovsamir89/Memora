package ai.memora.app.data

import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface CapturePersistence {
    fun load(): CaptureRead
    fun saveDraft(expectedVersion: Long, text: String): CaptureWrite
    fun submit(commandId: String, expectedVersion: Long, sourceId: String, revisionId: String,
        text: String, recordedAt: Instant, zone: String): CaptureWrite
    fun delete(sourceId: String, expectedRevisionId: String): CaptureWrite
    fun revise(sourceId: String, expectedRevisionId: String, revisionId: String, text: String, recordedAt: Instant): CaptureWrite = CaptureWrite.Unavailable
    fun archive(selections: List<CaptureArchiveSelection>, archived: Boolean): CaptureWrite
    fun export(): CaptureExport
    fun previewImport(json: String): CaptureImportPreview
    fun applyImport(json: String, ticket: CaptureImportTicket): CaptureWrite
}

class LocalCapturePersistence(private val store: EncryptedCaptureStore) : CapturePersistence {
    override fun load() = store.load()
    override fun saveDraft(expectedVersion: Long, text: String) = store.saveDraft(expectedVersion, text)
    override fun submit(commandId: String, expectedVersion: Long, sourceId: String, revisionId: String,
        text: String, recordedAt: Instant, zone: String) =
        store.submit(commandId, expectedVersion, sourceId, revisionId, text, recordedAt, zone)
    override fun delete(sourceId: String, expectedRevisionId: String) = store.delete(sourceId, expectedRevisionId)
    override fun revise(sourceId: String, expectedRevisionId: String, revisionId: String, text: String, recordedAt: Instant) =
        store.revise(sourceId, expectedRevisionId, revisionId, text, recordedAt)
    override fun archive(selections: List<CaptureArchiveSelection>, archived: Boolean) = store.archive(selections, archived)
    override fun export() = store.export()
    override fun previewImport(json: String) = store.previewImport(json)
    override fun applyImport(json: String, ticket: CaptureImportTicket) = store.applyImport(json, ticket)
}

data class CaptureSessionState(
    val ready: Boolean = false,
    val loading: Boolean = true,
    val draft: String = "",
    val savedDraft: String = "",
    val version: Long = 0,
    val captures: List<CaptureEntry> = emptyList(),
    val saving: Boolean = false,
    val submitting: Boolean = false,
    val retrySubmission: Boolean = false,
    val error: Boolean = false,
    val limitReached: Boolean = false,
    val managementBusy: Boolean = false,
    val managementError: Boolean = false,
    val importDone: Boolean = false,
    val importStale: Boolean = false,
) {
    val dirty: Boolean get() = draft != savedDraft
}

data class CaptureSubmissionIds(val command: String, val source: String, val revision: String,
    val recordedAt: Instant, val zone: String)

data class CaptureRevisionRequest(val sourceId: String, val previousId: String, val revisionId: String,
    val text: String, val recordedAt: Instant)

/** Calls and scope callbacks must share the UI dispatcher; disk work runs on [io]. */
class CaptureSession(
    private val store: CapturePersistence,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Pending(val ids: CaptureSubmissionIds, val version: Long, val text: String)

    private val mutableState = MutableStateFlow(CaptureSessionState())
    val state: StateFlow<CaptureSessionState> = mutableState.asStateFlow()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private var pending: Pending? = null
    private var submissionJob: Job? = null
    private var reloadJob: Job? = null
    private var managementJob: Job? = null
    private var closed = false
    private val worker: Job = scope.launch {
        when (val loaded = safely(CaptureRead.Unavailable) { store.load() }) {
            is CaptureRead.Ready -> mutableState.value = loaded.snapshot.toSessionState()
            CaptureRead.Unavailable -> mutableState.value = mutableState.value.copy(loading = false, error = true)
        }
        for (ignored in signal) mutex.withLock { persistLatest() }
    }

    fun edit(text: String) {
        val current = mutableState.value
        if (closed || !current.ready || current.submitting || current.retrySubmission || current.managementBusy ||
            !ai.memora.app.domain.TextSourceRules.validDraft(text)) return
        mutableState.value = current.copy(draft = text, error = false, limitReached = false)
        signal.trySend(Unit)
    }

    fun retrySave() {
        if (closed || !mutableState.value.ready || mutableState.value.retrySubmission || mutableState.value.submitting || mutableState.value.managementBusy) return
        mutableState.value = mutableState.value.copy(error = false, limitReached = false)
        signal.trySend(Unit)
    }

    fun retryLoad() {
        val current = mutableState.value
        if (closed || current.ready || current.loading) return
        mutableState.value = current.copy(loading = true, error = false)
        reloadJob = scope.launch {
            mutex.withLock {
                when (val loaded = safely(CaptureRead.Unavailable) { store.load() }) {
                    is CaptureRead.Ready -> mutableState.value = loaded.snapshot.toSessionState()
                    CaptureRead.Unavailable -> mutableState.value = mutableState.value.copy(loading = false, error = true)
                }
            }
        }
    }

    /** A failed/unknown submit is retried with the original exact IDs, text, version and time. */
    fun submit(ids: CaptureSubmissionIds) {
        val current = mutableState.value
        if (closed || !current.ready || current.submitting || current.managementBusy || (pending == null && current.draft.isBlank())) return
        mutableState.value = current.copy(submitting = true, error = false)
        submissionJob = scope.launch {
            mutex.withLock {
                if (pending == null) {
                    if (!persistLatest()) {
                        mutableState.value = mutableState.value.copy(submitting = false)
                        return@withLock
                    }
                    val saved = mutableState.value
                    pending = Pending(ids, saved.version, saved.savedDraft)
                }
                val operation = requireNotNull(pending)
                val result = safely(CaptureWrite.Unavailable) {
                    store.submit(operation.ids.command, operation.version, operation.ids.source, operation.ids.revision,
                        operation.text, operation.ids.recordedAt, operation.ids.zone)
                }
                if (result == CaptureWrite.Recorded || result == CaptureWrite.Duplicate) {
                    when (val loaded = safely(CaptureRead.Unavailable) { store.load() }) {
                        is CaptureRead.Ready -> {
                            pending = null
                            mutableState.value = loaded.snapshot.toSessionState()
                        }
                        CaptureRead.Unavailable -> mutableState.value = mutableState.value.copy(submitting = false, retrySubmission = true, error = true)
                    }
                } else if (result == CaptureWrite.Invalid || result == CaptureWrite.Conflict || result == CaptureWrite.LimitReached) {
                    pending = null
                    mutableState.value = mutableState.value.copy(submitting = false, retrySubmission = false, error = true,
                        limitReached = result == CaptureWrite.LimitReached)
                } else {
                    mutableState.value = mutableState.value.copy(submitting = false, retrySubmission = true, error = true)
                }
            }
        }
    }

    fun delete(sourceId: String, revisionId: String) {
        val current = mutableState.value
        if (closed || !current.ready || current.managementBusy || current.submitting || current.retrySubmission || current.saving || current.dirty) return
        mutableState.value = current.copy(managementBusy = true, managementError = false)
        managementJob = scope.launch {
            mutex.withLock {
                val result = safely(CaptureWrite.Unavailable) { store.delete(sourceId, revisionId) }
                val loaded = safely(CaptureRead.Unavailable) { store.load() } as? CaptureRead.Ready
                if (loaded == null) {
                    mutableState.value = mutableState.value.copy(managementBusy = false, managementError = true)
                } else {
                    val confirmedRemoved = loaded.snapshot.tombstones.any { it.sourceId == sourceId && it.revisionId == revisionId } &&
                        loaded.snapshot.captures.none { it.source.id == sourceId }
                    val success = confirmedRemoved && result in setOf(CaptureWrite.Recorded, CaptureWrite.Duplicate, CaptureWrite.Unavailable)
                    mutableState.value = loaded.snapshot.toSessionState().copy(managementError = !success)
                }
            }
        }
    }

    fun archive(selections: List<CaptureArchiveSelection>, archived: Boolean) {
        val current = mutableState.value
        if (closed || !current.ready || selections.isEmpty() || current.managementBusy || current.submitting ||
            current.retrySubmission || current.saving || current.dirty) return
        mutableState.value = current.copy(managementBusy = true, managementError = false)
        managementJob = scope.launch {
            mutex.withLock {
                val result = safely(CaptureWrite.Unavailable) { store.archive(selections, archived) }
                val loaded = safely(CaptureRead.Unavailable) { store.load() } as? CaptureRead.Ready
                if (loaded == null) {
                    mutableState.value = mutableState.value.copy(managementBusy = false, managementError = true)
                } else {
                    val confirmed = selections.all { selected ->
                        val entry = loaded.snapshot.captures.find { it.source.id == selected.sourceId }
                        val next = if (selected.organizationVersion == Long.MAX_VALUE) null else selected.organizationVersion + 1
                        entry != null && entry.source.revisions.last().id == selected.revisionId && entry.archived == archived &&
                            (entry.organizationVersion == selected.organizationVersion || entry.organizationVersion == next)
                    }
                    val success = confirmed && result in setOf(CaptureWrite.Recorded, CaptureWrite.Unchanged, CaptureWrite.Unavailable)
                    mutableState.value = loaded.snapshot.toSessionState().copy(managementError = !success)
                }
            }
        }
    }

    /** Unknown outcomes are checked by reading the exact revision and predecessor. */
    fun revise(request: CaptureRevisionRequest) {
        val current = mutableState.value
        if (closed || !current.ready || current.managementBusy || current.submitting || current.retrySubmission || current.saving || current.dirty) return
        mutableState.value = current.copy(managementBusy = true, managementError = false)
        managementJob = scope.launch {
            mutex.withLock {
                val result = safely(CaptureWrite.Unavailable) {
                    store.revise(request.sourceId, request.previousId, request.revisionId, request.text, request.recordedAt)
                }
                val loaded = safely(CaptureRead.Unavailable) { store.load() } as? CaptureRead.Ready
                if (loaded == null) {
                    mutableState.value = mutableState.value.copy(managementBusy = false, managementError = true)
                } else {
                    val revisions = loaded.snapshot.captures.find { it.source.id == request.sourceId }?.source?.revisions.orEmpty()
                    val index = revisions.indexOfFirst { it.id == request.revisionId }
                    val confirmed = index > 0 && revisions[index - 1].id == request.previousId &&
                        revisions[index].text == request.text && revisions[index].recordedAt == request.recordedAt
                    val success = confirmed && result in setOf(CaptureWrite.Recorded, CaptureWrite.Duplicate, CaptureWrite.Unavailable)
                    mutableState.value = loaded.snapshot.toSessionState().copy(managementError = !success)
                }
            }
        }
    }

    suspend fun export(): CaptureExport = mutex.withLock {
        val current = mutableState.value
        if (closed || !current.ready || current.submitting || current.retrySubmission || current.managementBusy || current.saving || current.dirty)
            return@withLock CaptureExport.Unavailable
        safely(CaptureExport.Unavailable) { store.export() }
    }

    suspend fun previewImport(json: String): CaptureImportPreview = mutex.withLock {
        val current = mutableState.value
        if (closed || !current.ready || current.submitting || current.retrySubmission || current.managementBusy || current.saving || current.dirty)
            return@withLock CaptureImportPreview.Unavailable
        safely(CaptureImportPreview.Unavailable) { store.previewImport(json) }
    }

    fun applyImport(json: String, ticket: CaptureImportTicket) {
        val current = mutableState.value
        if (closed || !current.ready || current.managementBusy || current.submitting || current.retrySubmission || current.saving || current.dirty) return
        mutableState.value = current.copy(managementBusy = true, managementError = false, importDone = false, importStale = false)
        managementJob = scope.launch {
            mutex.withLock {
                val result = safely(CaptureWrite.Unavailable) { store.applyImport(json, ticket) }
                val loaded = safely(CaptureRead.Unavailable) { store.load() } as? CaptureRead.Ready
                val success = result == CaptureWrite.Recorded || result == CaptureWrite.Unchanged
                mutableState.value = if (loaded != null) loaded.snapshot.toSessionState().copy(
                    managementError = !success && result != CaptureWrite.Conflict,
                    importDone = success, importStale = result == CaptureWrite.Conflict)
                else mutableState.value.copy(managementBusy = false, managementError = true)
            }
        }
    }

    private suspend fun persistLatest(): Boolean {
        while (true) {
            val current = mutableState.value
            if (!current.ready || current.retrySubmission) return false
            if (!current.dirty) {
                mutableState.value = current.copy(saving = false)
                return true
            }
            mutableState.value = current.copy(saving = true)
            val result = safely(CaptureWrite.Unavailable) { store.saveDraft(current.version, current.draft) }
            val latest = mutableState.value
            when (result) {
                CaptureWrite.Recorded -> mutableState.value = latest.copy(savedDraft = current.draft, version = current.version + 1, error = false)
                CaptureWrite.Unchanged -> mutableState.value = latest.copy(savedDraft = current.draft, error = false)
                CaptureWrite.LimitReached -> {
                    mutableState.value = latest.copy(saving = false, error = true, limitReached = true)
                    return false
                }
                CaptureWrite.Unavailable -> {
                    // A failed response may follow a successful atomic replacement. Re-read
                    // before deciding whether to retry with the now-stale version.
                    val readBack = safely(CaptureRead.Unavailable) { store.load() } as? CaptureRead.Ready
                    val snapshot = readBack?.snapshot
                    val afterRead = mutableState.value
                    if (current.version != Long.MAX_VALUE && snapshot?.draftVersion == current.version + 1 &&
                        snapshot.draft == current.draft && snapshot.captures == current.captures) {
                        mutableState.value = afterRead.copy(savedDraft = current.draft, version = snapshot.draftVersion, error = false)
                    } else {
                        mutableState.value = afterRead.copy(saving = false, error = true)
                        return false
                    }
                }
                else -> {
                    mutableState.value = latest.copy(saving = false, error = true)
                    return false
                }
            }
        }
    }

    fun close() { closed = true; signal.close(); worker.cancel(); submissionJob?.cancel(); reloadJob?.cancel(); managementJob?.cancel() }

    private suspend fun <T> safely(fallback: T, action: () -> T): T = withContext(io) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fallback }
    }

    private fun CaptureSnapshot.toSessionState() = CaptureSessionState(
        ready = true, loading = false, draft = draft, savedDraft = draft, version = draftVersion, captures = captures,
    )
}

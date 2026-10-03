package io.legado.app.ui.about

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.UpdateDialogRepository
import io.legado.app.data.repository.UpdateDialogRequest
import io.legado.app.ui.components.markdown.RichDocument
import io.legado.app.ui.components.markdown.projectTextDocument
import io.legado.app.utils.ConvertUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdateDownloadTarget {
    Primary,
    Backup,
    Mirror,
    AlternateMirror,
}

enum class UpdateDialogAction {
    Download,
    Browser,
    IgnoredNotice,
}

data class UpdateDialogEffect(
    val id: Long,
    val action: UpdateDialogAction,
    val url: String? = null,
    val fileName: String? = null,
)

data class UpdateDialogState(
    val request: UpdateDialogRequest? = null,
    val document: RichDocument = RichDocument(emptyList()),
    val metadata: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val effect: UpdateDialogEffect? = null,
    val finished: Boolean = false,
) {
    val canAct
        get() = request != null && !loading && !busy && effect == null && !finished

    val downloadTargets
        get() =
            request
                ?.let { info ->
                    if (info.beta) emptyList()
                    else
                        buildList {
                            if (!info.backupUrl.isNullOrBlank()) add(UpdateDownloadTarget.Backup)
                            if (!info.mirrorUrl.isNullOrBlank()) add(UpdateDownloadTarget.Mirror)
                            if (!info.alternateMirrorUrl.isNullOrBlank())
                                add(UpdateDownloadTarget.AlternateMirror)
                        }
                }
                .orEmpty()
}

internal fun formatUpdateMetadata(
    size: Long,
    createdAt: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildList {
    if (size > 0) add(ConvertUtils.formatFileSize(size))
    if (createdAt > 0)
        add(Instant.ofEpochMilli(createdAt).atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE))
}
    .joinToString(" · ")

/**
 * Native service/browser actions are delivered by the resumed host, never retained as callbacks.
 */
class UpdateDialogViewModel(
    private val repository: UpdateDialogRepository,
    private val saved: SavedStateHandle,
    requestId: String,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val id =
        saved.get<String>("update.request") ?: requestId.also { saved["update.request"] = it }
    private val mutable =
        MutableStateFlow(
            UpdateDialogState(
                finished = saved.get<Boolean>("update.finished") == true,
                loading = saved.get<Boolean>("update.finished") != true,
            )
        )
    val state = mutable.asStateFlow()
    private var work: Job? = null

    init {
        load()
    }

    fun load() {
        if (state.value.finished || work?.isActive == true) return
        mutable.value = state.value.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val request = repository.load(id)
                val document = withContext(compute) { projectTextDocument(request.body, "MD") }
                if (state.value.finished) return@launch
                mutable.value =
                    state.value.copy(
                        request = request,
                        document = document,
                        metadata = formatUpdateMetadata(request.size, request.createdAt),
                        loading = false,
                        effect = restoredEffect(request),
                    )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!state.value.finished)
                    mutable.value =
                        state.value.copy(
                            loading = false,
                            error = error.localizedMessage ?: error.toString(),
                        )
            }
        }
    }

    private fun url(request: UpdateDialogRequest, target: UpdateDownloadTarget): String? =
        when (target) {
            UpdateDownloadTarget.Primary -> request.url
            UpdateDownloadTarget.Backup -> request.backupUrl
            UpdateDownloadTarget.Mirror -> request.mirrorUrl
            UpdateDownloadTarget.AlternateMirror -> request.alternateMirrorUrl
        }

    fun download(target: UpdateDownloadTarget = UpdateDownloadTarget.Primary) {
        val state = state.value
        val request = state.request ?: return
        if (
            !state.canAct ||
                (target != UpdateDownloadTarget.Primary && target !in state.downloadTargets)
        )
            return
        if (url(request, target).isNullOrBlank() || request.fileName.isBlank()) return
        queue(UpdateDialogAction.Download, target)
    }

    fun browser() {
        val info = state.value.request ?: return
        if (!state.value.canAct || browserUrl(info).isBlank()) return
        queue(UpdateDialogAction.Browser)
    }

    private fun browserUrl(info: UpdateDialogRequest): String =
        if (info.beta) info.url else info.backupUrl.orEmpty().ifBlank { info.url }

    fun ignore() {
        val info = state.value.request ?: return
        if (!state.value.canAct || info.beta) return
        mutable.value = state.value.copy(busy = true, error = null)
        work = viewModelScope.launch {
            try {
                repository.ignore(info.version)
                mutable.value = state.value.copy(busy = false)
                queue(UpdateDialogAction.IgnoredNotice)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false,
                        error = error.localizedMessage ?: error.toString(),
                    )
            }
        }
    }

    private fun queue(
        action: UpdateDialogAction,
        target: UpdateDownloadTarget = UpdateDownloadTarget.Primary,
    ) {
        val sequence = (saved.get<Long>("update.sequence") ?: 0) + 1
        saved["update.sequence"] = sequence
        saved["update.effectId"] = sequence
        saved["update.action"] = action.name
        saved["update.target"] = target.name
        mutable.value =
            state.value.copy(effect = restoredEffect(state.value.request!!), error = null)
    }

    private fun restoredEffect(request: UpdateDialogRequest): UpdateDialogEffect? {
        val action =
            saved.get<String>("update.action")?.let {
                runCatching { UpdateDialogAction.valueOf(it) }.getOrNull()
            } ?: return null
        val token = saved.get<Long>("update.effectId") ?: return null
        val target =
            saved.get<String>("update.target")?.let {
                runCatching { UpdateDownloadTarget.valueOf(it) }.getOrNull()
            } ?: UpdateDownloadTarget.Primary
        return UpdateDialogEffect(
            token,
            action,
            when (action) {
                UpdateDialogAction.Download -> url(request, target)
                UpdateDialogAction.Browser -> browserUrl(request)
                UpdateDialogAction.IgnoredNotice -> null
            },
            if (action == UpdateDialogAction.Download) request.fileName else null,
        )
    }

    fun delivered(token: Long) {
        val effect = state.value.effect ?: return
        if (effect.id != token) return
        clearEffect()
        if (effect.action != UpdateDialogAction.Browser) finish()
    }

    fun failed(token: Long, message: String) {
        if (state.value.effect?.id != token) return
        clearEffect()
        mutable.value = state.value.copy(error = message)
    }

    private fun clearEffect() {
        saved.remove<String>("update.action")
        saved.remove<Long>("update.effectId")
        saved.remove<String>("update.target")
        mutable.value = state.value.copy(effect = null)
    }

    fun cancel() {
        if (state.value.busy || state.value.finished) return
        work?.cancel()
        clearEffect()
        finish()
    }

    private fun finish() {
        saved["update.finished"] = true
        mutable.value = state.value.copy(finished = true, loading = false)
    }

    fun stop() {
        work?.cancel()
    }

    override fun onCleared() {
        stop()
    }
}

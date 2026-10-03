package io.legado.app.ui.association.compose

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Public online-call compatibility over the private, owner-gated import state machine. */
open class OnlineAssociationCompatibilityModel
private constructor(
    savedState: SavedStateHandle,
    private val dependencies: AssociationDependencies,
) :
    AssociationImportViewModel(
        savedState,
        dependencies.sessions,
        dependencies.files,
        dependencies.online,
        dependencies.actions,
        dependencies.nativeResults,
    ) {
    constructor(
        application: Application,
        savedState: SavedStateHandle,
    ) : this(
        savedState,
        AssociationDependencies(application),
    )

    constructor(application: Application) : this(application, SavedStateHandle())

    private val textError = MutableStateFlow<String?>(null)
    val errorLive =
        combine(state, textError) { current, failure ->
                current.restoreError ?: current.session?.error ?: failure
            }
            .asLiveData()

    val intentHandled: Boolean
        get() = state.value.ticket != null || state.value.busy

    fun getText(url: String, success: (text: String) -> Unit) {
        val requestTicket = state.value.ticket
        val requestGeneration = state.value.session?.generation
        if (!acceptsCallback(requestTicket, requestGeneration)) return
        viewModelScope.launch {
            try {
                val text = dependencies.online.text(url)
                currentCoroutineContext().ensureActive()
                if (!sameOwner(requestTicket, requestGeneration)) return@launch
                success(text)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (sameOwner(requestTicket, requestGeneration)) {
                    textError.value = failure.localizedMessage ?: "Unknown import error"
                }
            }
        }
    }

    fun getReadConfig(url: String) = launchOnline(url, "readConfig")

    fun determineType(url: String) = launchOnline(url, "auto")

    fun importReadConfig() = confirmOperation("read-config")

    fun cancelReadConfigImport() = finishRequest()

    private fun launchOnline(url: String, path: String) {
        val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        start(
            AssociationInput(
                AssociationHostKind.Online,
                AssociationInputKind.View,
                listOf("legado://import/$path?src=$encoded"),
            )
        )
    }

    private fun sameOwner(ticket: String?, generation: Long?): Boolean {
        return acceptsCallback(ticket, generation)
    }
}

package io.legado.app.ui.association.compose

import android.os.Bundle
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import java.util.UUID

/** Default Intent extras must never become defaults for a private import session handle. */
fun associationCreationExtras(original: CreationExtras): CreationExtras =
    MutableCreationExtras(original).apply { this[DEFAULT_ARGS_KEY] = Bundle() }

/**
 * These models are ordinary ViewModels with Application dependencies, so the default saved-state
 * factory cannot infer their two-argument constructors. Install this exact-class factory as the
 * Activity default: child activityViewModels then use the same owner, key and restored handle.
 */
class AssociationViewModelFactory(
    private val fallback: ViewModelProvider.Factory,
    private val modelClass: Class<out ViewModel>,
    private val preparedTicket: () -> String?,
    private val create: (SavedStateHandle) -> ViewModel,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass != this.modelClass) return fallback.create(modelClass, extras)
        val savedState = associationCreationExtras(extras).createSavedStateHandle()
        savedState
            .keys()
            .filter { it != AssociationImportViewModel.TICKET_KEY }
            .forEach {
                savedState.remove<Any>(it)
            }
        if (savedState.get<String>(AssociationImportViewModel.TICKET_KEY) == null) {
            preparedTicket()?.let { ticket ->
                require(runCatching { UUID.fromString(ticket) }.isSuccess)
                savedState[AssociationImportViewModel.TICKET_KEY] = ticket
            }
        }
        return requireNotNull(modelClass.cast(create(savedState)))
    }
}

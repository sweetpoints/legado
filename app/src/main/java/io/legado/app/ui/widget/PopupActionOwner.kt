package io.legado.app.ui.widget

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** An ephemeral popup never shares its composition or saved-state namespace with another show. */
internal class PopupActionOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = saved.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun moveTo(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = state
    }

    fun close() {
        moveTo(Lifecycle.State.DESTROYED)
        viewModelStore.clear()
    }
}

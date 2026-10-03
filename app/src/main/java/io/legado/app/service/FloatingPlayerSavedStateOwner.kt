package io.legado.app.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Gives the service-hosted Compose window a saved-state registry with the service's lifecycle. The
 * floating controls have no restorable user input, so this owner intentionally starts empty.
 */
internal class FloatingPlayerSavedStateOwner(service: LifecycleOwner) : SavedStateRegistryOwner {
    private val ownerLifecycle = LifecycleRegistry(this)
    private val registryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = ownerLifecycle

    override val savedStateRegistry: SavedStateRegistry
        get() = registryController.savedStateRegistry

    init {
        registryController.performAttach()
        registryController.performRestore(null)
        service.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_ANY) {
                    ownerLifecycle.handleLifecycleEvent(event)
                }
            }
        )
    }
}

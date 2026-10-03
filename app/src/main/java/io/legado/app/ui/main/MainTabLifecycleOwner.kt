package io.legado.app.ui.main

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** Gives each pager page the lifecycle state of its own visibility. */
internal class MainTabLifecycleOwner : LifecycleOwner {
    override val lifecycle: LifecycleRegistry = LifecycleRegistry(this)

    fun update(parentState: Lifecycle.State, selected: Boolean) {
        val ceiling = if (selected) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
        val target =
            when {
                parentState == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
                parentState.ordinal < Lifecycle.State.CREATED.ordinal -> Lifecycle.State.INITIALIZED
                parentState.ordinal < ceiling.ordinal -> parentState
                else -> ceiling
            }
        lifecycle.currentState = target
    }
}

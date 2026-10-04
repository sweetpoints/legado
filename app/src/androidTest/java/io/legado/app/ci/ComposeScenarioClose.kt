package io.legado.app.ci

import android.app.Activity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario

/** Deliver the reader/editor's Compose exit effect before ActivityScenario blocks on destruction. */
internal fun <A : Activity> ActivityScenario<A>.closeAfterComposeExit(compose: ComposeTestRule) {
    if (state != Lifecycle.State.DESTROYED) {
        onActivity { it.finish() }
        compose.waitUntil(timeoutMillis = 20000) {
            compose.mainClock.advanceTimeByFrame()
            state == Lifecycle.State.DESTROYED
        }
    }
    close()
}

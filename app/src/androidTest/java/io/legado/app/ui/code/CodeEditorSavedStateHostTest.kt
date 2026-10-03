package io.legado.app.ui.code

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeEditorSavedStateHostTest {
    @Test
    fun largeLaunchPayloadLeavesIntentAndActualSavedBundleAfterPrivateAcceptance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = "// 😀\r\n".repeat(20_000)
        val scenario =
            ActivityScenario.launch<CodeEditActivity>(
                Intent(context, CodeEditActivity::class.java).apply {
                    putExtra("text", raw)
                    putExtra("title", "SavedState regression")
                    putExtra("returnUnchangedText", true)
                }
            )
        try {
            awaitPrivateSession(scenario, raw)
            scenario.onActivity { activity ->
                assertFalse(activity.intent.hasExtra("text"))
                assertFalse(activity.intent.hasExtra("title"))
                val saved = Bundle()
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnSaveInstanceState(activity, saved)
                assertFalse(containsPayload(saved, raw))
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(saved)
                    assertTrue(
                        "Saved Bundle must hold only small editor identity/state",
                        parcel.dataSize() < 64 * 1024,
                    )
                } finally {
                    parcel.recycle()
                }
            }
            scenario.recreate()
            awaitPrivateSession(scenario, raw)
            scenario.onActivity {
                assertFalse(it.intent.hasExtra("text"))
                assertEquals(
                    raw,
                    ViewModelProvider(it)[CodeEditorComposeViewModel::class.java]
                        .state
                        .value
                        .session!!
                        .text,
                )
            }
        } finally {
            scenario.close()
        }
    }

    private fun awaitPrivateSession(
        scenario: ActivityScenario<CodeEditActivity>,
        expected: String,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity {
                val state =
                    ViewModelProvider(it)[CodeEditorComposeViewModel::class.java].state.value
                ready =
                    !state.busy && state.session?.text == expected && !it.intent.hasExtra("text")
            }
            if (ready) return
            android.os.SystemClock.sleep(25)
        }
        error("Private Code session was not accepted")
    }

    @Suppress("DEPRECATION")
    private fun containsPayload(value: Any?, payload: String): Boolean =
        when (value) {
            is String -> value == payload
            is Bundle -> value.keySet().any { containsPayload(value.get(it), payload) }
            is List<*> -> value.any { containsPayload(it, payload) }
            is Array<*> -> value.any { containsPayload(it, payload) }
            else -> false
        }
}

package io.legado.app.ui.code

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CurlConversionRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun copyWaitsForResumeAndConsumePrecedesHostPauseWithoutReplayingOnRecreation() {
        val owner = Owner()
        lateinit var model: CurlConversionViewModel
        val copies = mutableListOf<String>()
        var visible by mutableStateOf(true)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            model = CurlConversionViewModel(Fake(), SavedStateHandle(), "seed", true)
        }
        compose.setContent {
            if (visible)
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LegadoComposeTheme {
                        CurlConversionRoute(
                            model,
                            { true },
                            { text ->
                                assertTrue(model.state.value.effects.isEmpty())
                                copies += text
                                owner.registry.currentState = Lifecycle.State.CREATED
                            },
                            { _, _ -> error("insert unused") },
                            { error(it) },
                            {},
                        )
                    }
                }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.runOnIdle { model.copy() }
        compose.waitUntil { model.state.value.effects.isNotEmpty() }
        assertTrue(copies.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { copies.size == 1 }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            visible = true
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(listOf("output"), copies)
    }

    @Test
    fun asynchronousInsertDisablesRepeatedClicksAndRetainedResultClosesAfterCompositionRecreation() {
        lateinit var model: CurlConversionViewModel
        var visible by mutableStateOf(true)
        var inserts = 0
        var closes = 0
        var callback: ((Boolean) -> Unit)? = null
        compose.runOnIdle {
            model = CurlConversionViewModel(Fake(), SavedStateHandle(), "seed", true)
        }
        compose.setContent {
            if (visible)
                LegadoComposeTheme {
                    CurlConversionRoute(
                        model,
                        { true },
                        {},
                        { text, result ->
                            assertEquals("output", text)
                            assertTrue(model.state.value.effects.isEmpty())
                            callback = result
                            inserts++
                        },
                        {},
                        { closes++ },
                    )
                }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("curl-converter-insert").performClick()
        compose.waitUntil { inserts == 1 }
        compose.onNodeWithTag("curl-converter-insert").assertIsNotEnabled()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        assertEquals(1, inserts)
        compose.runOnIdle { callback!!(true) }
        compose.waitUntil { closes == 1 }
    }

    @Test
    fun directionClearsReadOnlyOutputAndCopyAndWritableInsertControlsFollowState() {
        lateinit var model: CurlConversionViewModel
        compose.runOnIdle {
            model = CurlConversionViewModel(Fake(), SavedStateHandle(), "seed", false)
        }
        compose.setContent {
            LegadoComposeTheme { CurlConversionRoute(model, { true }, {}, { _, _ -> }, {}, {}) }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("curl-converter-output").assertTextContains("output")
        compose.onNodeWithTag("curl-converter-insert").assertDoesNotExist()
        compose.onNodeWithTag("curl-converter-direction-AnalyzeToCurl").performClick()
        compose.onNodeWithTag("curl-converter-copy").assertIsNotEnabled()
        compose.onNodeWithTag("curl-converter-input").assertTextContains("curl https://example.com")
        compose
            .onNodeWithTag("curl-converter-output")
            .assert(
                SemanticsMatcher.keyNotDefined(
                    androidx.compose.ui.semantics.SemanticsActions.SetText
                )
            )
        assertEquals("", model.state.value.draft.output)
    }

    @Test
    fun insertFailureShowsToastThenAllowsRetryAndLocalizedConversionErrorIncludesDetail() {
        lateinit var model: CurlConversionViewModel
        val messages = mutableListOf<String>()
        var inserts = 0
        compose.runOnIdle {
            model = CurlConversionViewModel(Fake(), SavedStateHandle(), "seed", true)
        }
        compose.setContent {
            LegadoComposeTheme {
                CurlConversionRoute(
                    model,
                    { true },
                    {},
                    { _, result ->
                        inserts++
                        result(false)
                    },
                    { messages += it },
                    {},
                )
            }
        }
        compose.waitUntil { !model.state.value.loading }
        compose.onNodeWithTag("curl-converter-insert").performClick()
        compose.waitUntil { messages.size == 1 }
        compose.onNodeWithTag("curl-converter-insert").assertIsEnabled()
        compose.onNodeWithTag("curl-converter-insert").performClick()
        compose.waitUntil { inserts == 2 && messages.size == 2 }
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            context.getString(io.legado.app.R.string.curl_converter_insert_failed),
            messages.first(),
        )
        assertEquals(
            context.getString(io.legado.app.R.string.curl_converter_unsupported_method, "DELETE"),
            curlNoticeText(
                context,
                CurlEffect(
                    1,
                    CurlAction.Toast,
                    CurlNotice.Conversion,
                    io.legado.app.model.analyzeRule.CurlAnalyzeUrlConverter.ErrorReason
                        .UNSUPPORTED_METHOD,
                ),
                "DELETE",
            ),
        )
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Fake : CurlConversionRepository {
        val drafts = mutableMapOf<String, CurlConversionDraft>()

        override suspend fun restore(session: String, inputKey: String?) =
            drafts[session]
                ?: if (inputKey == null) CurlConversionDraft()
                else CurlConversionDraft("curl https://example.com", "output")

        override suspend fun save(session: String, draft: CurlConversionDraft) {
            drafts[session] = draft
        }

        override suspend fun convert(input: String, direction: CurlDirection) = "converted"
    }
}

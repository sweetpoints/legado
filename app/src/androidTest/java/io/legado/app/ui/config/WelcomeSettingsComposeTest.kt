package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.preferences.*
import io.legado.app.model.welcome.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class WelcomeSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private val owners = mutableListOf<ViewModelStore>()

    @After
    fun clear() {
        compose.runOnIdle { owners.forEach { it.clear() } }
    }

    private fun initial() =
        WelcomeSettingsState(loading = false, settings = WelcomeSettingsSnapshot())

    private fun actions() = WelcomeSettingsActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {})

    private fun row(key: String) =
        compose
            .onNodeWithTag("welcome-settings-list")
            .performScrollToNode(hasTestTag("welcome-row-$key"))
            .let { compose.onNodeWithTag("welcome-row-$key") }

    @Test
    fun exactMillisecondsButtonsAndSliderKeepOriginalRangeAndOneMillisecondSteps() {
        var state by mutableStateOf(initial())
        compose.setContent {
            LegadoComposeTheme {
                WelcomeSettingsScreen(
                    state,
                    actions()
                        .copy(
                            step = {
                                state =
                                    state.copy(
                                        milliseconds = (state.milliseconds + it).coerceIn(0, 800)
                                    )
                            },
                            milliseconds = { state = state.copy(milliseconds = it) },
                        ),
                )
            }
        }
        compose.onNodeWithTag("welcome-time-plus").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("welcome-time-value").assertTextEquals("501 ms")
        compose.onNodeWithTag("welcome-time-minus").performClick()
        compose.onNodeWithTag("welcome-time-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) {
            it(800f)
        }
        compose.onNodeWithTag("welcome-time-value").assertTextEquals("800 ms")
        compose.onNodeWithTag("welcome-time-plus").assertIsNotEnabled()
        compose.onNodeWithTag("welcome-time-slider").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) {
            it(0f)
        }
        compose.onNodeWithTag("welcome-time-minus").assertIsNotEnabled()
        compose.onNodeWithTag("welcome-time-value").assertTextEquals("0 ms")
    }

    @Test
    fun allFourDisplaySwitchesRemainIndependentWhenImagesAreEmptyAndCustomIsDisabled() {
        var state by mutableStateOf(initial())
        val events = mutableListOf<Pair<WelcomeSwitch, Boolean>>()
        compose.setContent {
            LegadoComposeTheme {
                WelcomeSettingsScreen(
                    state,
                    actions()
                        .copy(
                            boolean = { key, value ->
                                events += key to value
                                state =
                                    state.copy(
                                        settings =
                                            state.settings!!.copy(
                                                switches =
                                                    state.settings!!.switches + (key to value)
                                            )
                                    )
                            }
                        ),
                )
            }
        }
        listOf(
                WelcomeSwitch.DayText,
                WelcomeSwitch.DayIcon,
                WelcomeSwitch.NightText,
                WelcomeSwitch.NightIcon,
            )
            .forEach { key ->
                row(key.key).assertIsEnabled().assertIsOn().performClick().assertIsOff()
            }
        assertEquals(
            listOf(
                    WelcomeSwitch.DayText,
                    WelcomeSwitch.DayIcon,
                    WelcomeSwitch.NightText,
                    WelcomeSwitch.NightIcon,
                )
                .map { it to false },
            events,
        )
        assertFalse(state.settings!!.switches.getValue(WelcomeSwitch.Custom))
    }

    @Test
    fun imagePopupSelectAndDeleteCarryNightIdentityAndHaveAccessibleTouchTargets() {
        var state by mutableStateOf(initial().copy(popupNight = true))
        val selected = mutableListOf<Boolean>()
        val deleted = mutableListOf<Boolean>()
        compose.setContent {
            LegadoComposeTheme {
                WelcomeSettingsScreen(
                    state,
                    actions().copy(picker = { selected += it }, removeImage = { deleted += it }),
                )
            }
        }
        compose.onNodeWithTag("welcome-image-select").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(true), selected)
        compose.onNodeWithTag("welcome-image-delete").performClick()
        assertEquals(listOf(true), deleted)
        compose.runOnIdle { state = state.copy(popupNight = false) }
        compose.onNodeWithTag("welcome-image-delete").performClick()
        assertEquals(listOf(true, false), deleted)
    }

    @Test
    fun searchByNightCategoryScrollsToRowWithoutMutatingItsSwitch() {
        var search by
            mutableStateOf<String?>(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getString(io.legado.app.R.string.night)
            )
        var selected = 0
        var changed = 0
        compose.setContent {
            LegadoComposeTheme {
                WelcomeSettingsScreen(
                    initial(),
                    actions().copy(boolean = { _, _ -> changed++ }),
                    search,
                    searchFinished = {
                        search = null
                        selected++
                    },
                )
            }
        }
        compose
            .onNodeWithTag("welcome-search-${WelcomeSwitch.NightIcon.key}")
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag("welcome-row-${WelcomeSwitch.NightIcon.key}")
            .assertIsDisplayed()
            .assertIsOn()
        assertEquals(1, selected)
        assertEquals(0, changed)
    }

    @Test
    fun initializationFailureAndBusyStateDisableMutationsAndExposeExplicitRetry() {
        var state by mutableStateOf(initial().copy(failed = true, error = "read failed"))
        var retried = 0
        compose.setContent {
            LegadoComposeTheme {
                WelcomeSettingsScreen(state, actions().copy(retry = { retried++ }))
            }
        }
        compose.onNodeWithTag("welcome-settings-retry").performClick()
        assertEquals(1, retried)
        row(WelcomeSwitch.Custom.key).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(failed = false, busy = true) }
        row("day-image").assertIsNotEnabled()
        row(WelcomeSwitch.NightText.key).assertIsNotEnabled()
    }

    @Test
    fun pausedPickerDeliveryResumesOnceAndRecompositionCannotRelaunchConsumedEvent() {
        val repo = Repo()
        val registryOwner = Owner()
        val registry = registryOwner.lifecycle
        lateinit var model: WelcomeSettingsViewModel
        val launches = mutableListOf<Boolean>()
        compose.runOnIdle {
            registry.currentState = Lifecycle.State.STARTED
            model = WelcomeSettingsViewModel(repo, Inputs(), SavedStateHandle())
            owners += ViewModelStore().apply { put("vm", model) }
        }
        val owner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle = registry
            }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme { WelcomeSettingsRoute(model, { true }, { launches += it }) }
            }
        }
        compose.waitUntil {
            compose.mainClock.advanceTimeByFrame()
            model.state.value.settings != null
        }
        compose.runOnIdle { model.picker(true) }
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil {
            compose.mainClock.advanceTimeByFrame()
            launches.size == 1
        }
        compose.runOnIdle {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals(listOf(true), launches)
            assertNull(model.state.value.picker)
        }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private class Inputs : WelcomeImageInputRepository {
        override suspend fun open(session: String) = WelcomeImageDraft()

        override suspend fun write(session: String, draft: WelcomeImageDraft) {}

        override suspend fun release(session: String) {}
    }

    private class Repo : WelcomeSettingsRepository {
        val value = MutableStateFlow(WelcomeSettingsSnapshot())

        override fun observe(): Flow<WelcomeSettingsSnapshot> = value

        override suspend fun load() = value.value

        override suspend fun milliseconds(value: Int) {}

        override suspend fun boolean(key: WelcomeSwitch, value: Boolean) {}

        override suspend fun image(night: Boolean, uri: String?) {}
    }
}

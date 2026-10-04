package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.preferences.*
import io.legado.app.model.cover.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class CoverSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private val owners = mutableListOf<ViewModelStore>()

    @After
    fun clear() {
        compose.runOnIdle { owners.forEach { it.clear() } }
    }

    private fun initial() = CoverSettingsState(loading = false, settings = CoverSettingsSnapshot())

    private fun actions() = CoverSettingsActions({ _, _ -> }, {}, {}, {}, {}, {}, {})

    private fun row(key: String) =
        compose
            .onNodeWithTag("cover-settings-list")
            .performScrollToNode(hasTestTag("cover-row-$key"))
            .let { compose.onNodeWithTag("cover-row-$key") }

    @Test
    fun nameDisablesOnlyItsOwnAuthorWhileRetainingCheckedValueAndNightCanToggle() {
        var state by mutableStateOf(initial())
        val changes = mutableListOf<Pair<CoverSettingSwitch, Boolean>>()
        compose.setContent {
            LegadoComposeTheme {
                CoverSettingsScreen(
                    state,
                    actions()
                        .copy(
                            boolean = { key, value ->
                                changes += key to value
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
        row(CoverSettingSwitch.DayName.key).assertHeightIsAtLeast(48.dp).performClick()
        row(CoverSettingSwitch.DayAuthor.key).assertIsNotEnabled().assertIsOn()
        row(CoverSettingSwitch.NightAuthor.key).assertIsEnabled().performClick().assertIsOff()
        assertEquals(
            listOf(CoverSettingSwitch.DayName to false, CoverSettingSwitch.NightAuthor to false),
            changes,
        )
    }

    @Test
    fun allImageTargetsAndRulesFontDestinationsAreTypedAndPopupKeepsTarget() {
        val images = mutableListOf<CoverSettingImage>()
        val nav = mutableListOf<CoverDestination>()
        val removed = mutableListOf<CoverSettingImage>()
        var state by mutableStateOf(initial())
        compose.setContent {
            LegadoComposeTheme {
                CoverSettingsScreen(
                    state,
                    actions()
                        .copy(
                            image = { images += it },
                            destination = { nav += it },
                            remove = { removed += it },
                        ),
                )
            }
        }
        row("rules").performClick()
        row("font").performClick()
        assertEquals(listOf(CoverDestination.Rules, CoverDestination.Font), nav)
        CoverSettingImage.entries.forEach { row(it.key).performClick() }
        assertEquals(CoverSettingImage.entries, images)
        compose.runOnIdle { state = state.copy(popup = CoverSettingImage.RecordNight) }
        compose.onNodeWithTag("cover-image-delete").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(CoverSettingImage.RecordNight), removed)
    }

    @Test
    fun categorySearchHasStableExactTargetAndDoesNotToggleAuthorOrNavigate() {
        var search by
            mutableStateOf<String?>(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.night)
            )
        var selected = 0
        var mutations = 0
        compose.setContent {
            LegadoComposeTheme {
                CoverSettingsScreen(
                    initial(),
                    actions()
                        .copy(boolean = { _, _ -> mutations++ }, destination = { mutations++ }),
                    search,
                    {
                        search = null
                        selected++
                    },
                )
            }
        }
        compose
            .onNodeWithTag("cover-search-${CoverSettingSwitch.NightAuthor.key}")
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag("cover-row-${CoverSettingSwitch.NightAuthor.key}")
            .assertIsDisplayed()
            .assertIsOn()
        assertEquals(1, selected)
        assertEquals(0, mutations)
    }

    @Test
    fun loadingFailureDisablesPlatformDestinationsAndExposesExplicitRetry() {
        var retries = 0
        compose.setContent {
            LegadoComposeTheme {
                CoverSettingsScreen(
                    initial().copy(failed = true, error = "load failed"),
                    actions().copy(retry = { retries++ }),
                )
            }
        }
        row("rules").assertIsNotEnabled()
        row(CoverSettingImage.RecordDay.key).assertIsNotEnabled()
        compose.onNodeWithTag("cover-settings-list")
            .performScrollToNode(hasTestTag("cover-settings-retry"))
        compose.onNodeWithTag("cover-settings-retry").assertIsDisplayed().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun pausedNavigationAndPickerResumeOnceAndCannotRepeatAfterStopStart() {
        val registryOwner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this)
            }
        lateinit var model: CoverSettingsViewModel
        val images = mutableListOf<CoverSettingImage>()
        val nav = mutableListOf<CoverDestination>()
        compose.runOnIdle {
            registryOwner.lifecycle.currentState = Lifecycle.State.STARTED
            model = CoverSettingsViewModel(Repo(), Inputs(), SavedStateHandle())
            owners += ViewModelStore().apply { put("vm", model) }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides registryOwner) {
                LegadoComposeTheme {
                    CoverSettingsRoute(model, { true }, { images += it }, { nav += it })
                }
            }
        }
        compose.waitUntil {
            compose.mainClock.advanceTimeByFrame()
            model.state.value.settings != null
        }
        compose.runOnIdle {
            model.picker(CoverSettingImage.RecordDay)
            model.destination(CoverDestination.Rules)
        }
        compose.runOnIdle {
            assertTrue(images.isEmpty())
            assertTrue(nav.isEmpty())
            registryOwner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil {
            compose.mainClock.advanceTimeByFrame()
            images.size == 1 && nav.size == 1
        }
        compose.runOnIdle {
            registryOwner.lifecycle.currentState = Lifecycle.State.STARTED
            registryOwner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle {
            assertEquals(listOf(CoverSettingImage.RecordDay), images)
            assertEquals(listOf(CoverDestination.Rules), nav)
        }
    }

    private class Inputs : CoverImageInputRepository {
        override suspend fun open(session: String) = CoverImageDraft()

        override suspend fun write(session: String, draft: CoverImageDraft) {}

        override suspend fun release(session: String) {}
    }

    private class Repo : CoverSettingsRepository {
        override fun observe(): Flow<CoverSettingsSnapshot> = flowOf(CoverSettingsSnapshot())

        override suspend fun load() = CoverSettingsSnapshot()

        override suspend fun boolean(key: CoverSettingSwitch, value: Boolean) {}

        override suspend fun image(key: CoverSettingImage, uri: String?) {}
    }
}

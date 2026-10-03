package io.legado.app.ui.config

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.preferences.*
import io.legado.app.model.theme.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class ThemeSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<ThemeSettingsViewModel>()

    @After
    fun clear() {
        compose.runOnIdle {
            models.forEach {
                it.stop()
                it.viewModelScope.cancel()
            }
        }
    }

    private fun settings() =
        ThemeSettingsSnapshot(
            launcherAvailable = true,
            wallpaperAvailable = true,
            systemFontScale = 1.26f,
            colors = ThemeColor.entries.associateWith { if (it.night) 0xff123456.toInt() else -1 },
            elevation = 4,
            dayImage = "full/day/path",
        )

    private fun state() = ThemeSettingsState(loading = false, settings = settings())

    private fun actions() =
        ThemeSettingsActions({ _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun row(key: String) =
        compose
            .onNodeWithTag("theme-settings-list")
            .performScrollToNode(hasTestTag("theme-row-$key"))
            .let { compose.onNodeWithTag("theme-row-$key") }

    @Test
    fun allSettingsAndDayNightCategoriesStayAvailableAndUnsupportedPlatformRowsAreHidden() {
        var state by mutableStateOf(state())
        compose.setContent { LegadoComposeTheme { ThemeSettingsScreen(state, actions()) } }
        row("launcher").assertHeightIsAtLeast(48.dp)
        row("welcome").assertHasClickAction()
        row("cover").assertHasClickAction()
        row("themes").assertHasClickAction()
        row("bottomSkin").assertHasClickAction()
        row("save-night").assertHasClickAction()
        row("day-category").assertHasNoClickAction()
        row("night-category").assertHasNoClickAction()
        compose.runOnIdle {
            state =
                state.copy(
                    settings =
                        state.settings!!.copy(launcherAvailable = false, wallpaperAvailable = false)
                )
        }
        compose.onNodeWithTag("theme-row-launcher").assertDoesNotExist()
        compose.onNodeWithTag("theme-row-${ThemeSwitch.WallpaperFollow.key}").assertDoesNotExist()
        row("font").assertHasClickAction()
    }

    @Test
    fun switchRowsUseTypedKeysAndWallpaperAutoTracksFollowDependency() {
        var state by mutableStateOf(state())
        val events = mutableListOf<Pair<ThemeSwitch, Boolean>>()
        compose.setContent {
            LegadoComposeTheme {
                ThemeSettingsScreen(
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
        row(ThemeSwitch.WallpaperAuto.key).assertIsNotEnabled()
        row(ThemeSwitch.WallpaperFollow.key).performClick()
        row(ThemeSwitch.WallpaperAuto.key).assertIsEnabled().performClick()
        assertEquals(
            listOf(ThemeSwitch.WallpaperFollow to true, ThemeSwitch.WallpaperAuto to false),
            events,
        )
        row(ThemeSwitch.DayNavigation.key).performClick()
        row(ThemeSwitch.NightNavigation.key).performClick()
        assertEquals(ThemeSwitch.NightNavigation to true, events.last())
    }

    @Test
    fun numericInputClampsAtBoundaryRestoresIncompleteEmptyAndDefaultIsExplicit() {
        var state by mutableStateOf(state().copy(popup = ThemeSettingsPopup.Font, number = 16))
        val confirmations = mutableListOf<Boolean>()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            LegadoComposeTheme {
                ThemeSettingsScreen(
                    state,
                    actions()
                        .copy(
                            number = { state = state.copy(number = it) },
                            confirm = { confirmations += it },
                        ),
                )
            }
        }
        compose.onNodeWithTag("theme-number-input").performTextReplacement("999")
        compose.onNodeWithTag("theme-number-input").assertTextEquals("16")
        compose.onNodeWithTag("theme-number-input").performTextReplacement("")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("theme-number-input").assertTextEquals("")
        compose.onNodeWithTag("theme-number-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("theme-number-default").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(true), confirmations)
        compose.onNodeWithTag("theme-number-input").performTextReplacement("8")
        compose.onNodeWithTag("theme-number-confirm").performClick()
        assertEquals(listOf(true, false), confirmations)
    }

    @Test
    fun colorPresetShadesRgbAndHexEditDraftAndValidationKeepsSaveReachable() {
        var state by
            mutableStateOf(
                state()
                    .copy(
                        popup = ThemeSettingsPopup.Color,
                        colorKey = ThemeColor.DayPrimary,
                        color = 0xff123456.toInt(),
                    )
            )
        var confirms = 0
        compose.setContent {
            LegadoComposeTheme {
                ThemeSettingsScreen(
                    state,
                    actions()
                        .copy(
                            color = { state = state.copy(color = it or 0xff000000.toInt()) },
                            confirm = { confirms++ },
                        ),
                )
            }
        }
        compose.onNodeWithTag("theme-color-shade-0").performScrollTo().performClick()
        assertNotEquals(0xff123456.toInt(), state.color)
        compose.onNodeWithTag("theme-color-hex").performScrollTo().performTextReplacement("#00AA55")
        assertEquals(0xff00aa55.toInt(), state.color)
        compose.onNodeWithTag("theme-color-hex").performTextReplacement("#bad")
        compose.onNodeWithTag("theme-color-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("theme-color-hex").performTextReplacement("#FFFFFF")
        compose.onNodeWithTag("theme-color-confirm").performClick()
        assertEquals(1, confirms)
        compose.runOnIdle { state = state.copy(problem = ThemeSettingsProblem.DayTooDark) }
        compose.onNodeWithTag("theme-color-validation").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backgroundActionsHaveSeparateDayNightTargetsAndDeleteOnlyExistingImage() {
        var state by mutableStateOf(state().copy(popup = ThemeSettingsPopup.DayBackground))
        val events = mutableListOf<ThemeSettingsDestination>()
        val deleted = mutableListOf<Boolean>()
        compose.setContent {
            LegadoComposeTheme {
                ThemeSettingsScreen(
                    state,
                    actions().copy(destination = { events += it }, removeImage = { deleted += it }),
                )
            }
        }
        compose.onNodeWithTag("theme-image-blur").performClick()
        compose.onNodeWithTag("theme-image-select").performClick()
        compose.onNodeWithTag("theme-image-delete").performClick()
        assertEquals(
            listOf(ThemeSettingsDestination.BlurDay, ThemeSettingsDestination.ImageDay),
            events,
        )
        assertEquals(listOf(false), deleted)
        compose.runOnIdle { state = state.copy(popup = ThemeSettingsPopup.NightBackground) }
        compose.onNodeWithTag("theme-image-delete").assertDoesNotExist()
        compose.onNodeWithTag("theme-image-select").performClick()
        assertEquals(ThemeSettingsDestination.ImageNight, events.last())
    }

    @Test
    fun saveThemeNameAndLauncherSelectionDeliverExactValuesWithoutChangingOtherFields() {
        var state by mutableStateOf(state().copy(popup = ThemeSettingsPopup.SaveNight))
        var saved = 0
        var launcher = ""
        compose.setContent {
            LegadoComposeTheme {
                ThemeSettingsScreen(
                    state,
                    actions()
                        .copy(
                            name = { state = state.copy(name = it) },
                            confirm = { saved++ },
                            launcher = { launcher = it },
                        ),
                )
            }
        }
        compose.onNodeWithTag("theme-save-name").performTextReplacement("  exact theme name  ")
        compose.onNodeWithTag("theme-save-confirm").performClick()
        assertEquals("  exact theme name  ", state.name)
        assertEquals(1, saved)
        compose.runOnIdle { state = state.copy(popup = ThemeSettingsPopup.Launcher) }
        compose.onNodeWithTag("theme-launcher-launcher6").performScrollTo().performClick()
        assertEquals("launcher6", launcher)
    }

    @Test
    fun searchMatchesCategoryAndScrollsToSelectedRowWithoutApplyingIt() {
        var search by
            mutableStateOf<String?>(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.night)
            )
        var selected = 0
        var changes = 0
        compose.setContent {
            LegadoComposeTheme {
                ThemeSettingsScreen(
                    state(),
                    actions().copy(popup = { _, _ -> changes++ }),
                    search = search,
                    searchFinished = {
                        search = null
                        selected++
                    },
                )
            }
        }
        compose.onNodeWithTag("theme-search-save-night").performScrollTo().performClick()
        compose.onNodeWithTag("theme-row-save-night").assertIsDisplayed()
        assertEquals(1, selected)
        assertEquals(0, changes)
    }

    @Test
    fun busyAndInitializationFailureDisableMutationsButShowExplicitRetry() {
        var state by mutableStateOf(state().copy(busy = true))
        var retry = 0
        compose.setContent {
            LegadoComposeTheme { ThemeSettingsScreen(state, actions().copy(retry = { retry++ })) }
        }
        compose.onNodeWithTag("theme-settings-mode").assertIsNotEnabled()
        row("font").assertIsNotEnabled()
        compose.runOnIdle {
            state = state.copy(busy = false, failed = true, error = "initial read failed")
        }
        compose.onNodeWithTag("theme-settings-error").assertTextEquals("initial read failed")
        compose.onNodeWithTag("theme-settings-retry").performClick()
        assertEquals(1, retry)
    }

    @Test
    fun routePausesHostNavigationAndConsumesBeforeSingleResumeDelivery() {
        val owner = Owner()
        val saved = SavedStateHandle()
        lateinit var vm: ThemeSettingsViewModel
        var launches = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            vm = ThemeSettingsViewModel(Repo(), Names(), saved)
            models += vm
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ThemeSettingsRoute(
                        vm,
                        { true },
                        {
                            assertNull(vm.state.value.event)
                            assertEquals(ThemeSettingsDestination.ImageNight, it)
                            launches++
                        },
                    )
                }
            }
        }
        compose.waitUntil(5000) { !vm.state.value.loading }
        compose.runOnIdle { vm.destination(ThemeSettingsDestination.ImageNight) }
        assertEquals(0, launches)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { launches == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, launches)
        assertNull(saved.get<String>("event"))
    }

    @Test
    fun routeDeliversDownloadedToastOnlyOnResumeAndConsumesBeforeHostCallback() {
        val owner = Owner()
        lateinit var vm: ThemeSettingsViewModel
        var messages = 0
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            vm = ThemeSettingsViewModel(Repo(), Names(), SavedStateHandle())
            models += vm
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ThemeSettingsRoute(
                        vm,
                        { true },
                        {},
                        message = {
                            assertFalse(vm.state.value.downloaded)
                            assertEquals("设定成功", it)
                            messages++
                        },
                    )
                }
            }
        }
        compose.waitUntil(5000) { !vm.state.value.loading }
        compose.runOnIdle { vm.background(false, "https://example.com/image") }
        compose.waitUntil(5000) { vm.state.value.downloaded }
        assertEquals(0, messages)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { messages == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, messages)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    private class Names : ThemeNameDraftRepository {
        var value = ThemeNameDraft()

        override suspend fun open(session: String) = value

        override suspend fun write(session: String, draft: ThemeNameDraft) {
            value = draft
        }

        override suspend fun release(session: String) = Unit
    }

    private class Repo : ThemeSettingsRepository {
        val value =
            MutableStateFlow(
                ThemeSettingsSnapshot(colors = ThemeColor.entries.associateWith { -1 })
            )

        override fun observe(): Flow<ThemeSettingsSnapshot> = value

        override suspend fun load() = value.value

        override suspend fun boolean(key: ThemeSwitch, value: Boolean) = Unit

        override suspend fun color(key: ThemeColor, value: Int) = Unit

        override suspend fun launcher(value: String) = Unit

        override suspend fun elevation(value: Int?) = Unit

        override suspend fun font(value: Int?) = Unit

        override suspend fun toggleNight() = Unit

        override suspend fun saveTheme(night: Boolean, name: String) = Unit

        override suspend fun image(night: Boolean, uri: String?) = Unit

        override suspend fun refreshTheme(night: Boolean) = Unit
    }
}

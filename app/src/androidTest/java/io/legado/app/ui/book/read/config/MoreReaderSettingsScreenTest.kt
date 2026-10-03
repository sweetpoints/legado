package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.MoreReaderSetting
import io.legado.app.data.preferences.MoreReaderSettings
import io.legado.app.data.preferences.MoreReaderSettingsRepository
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.defaultSharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoreReaderSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val keys =
        listOf(
            PreferKey.hideNavigationBar,
            PreferKey.highlightActionTrigger,
            PreferKey.mouseWheelPage,
            PreferKey.mouseWheelScrollSpeed,
            PreferKey.pageTouchSlop,
        )
    private val savedValues = keys.associateWith { preferences.all[it] }
    private var observation: AutoCloseable? = null

    @Before
    fun setUp() {
        preferences
            .edit()
            .putBoolean(PreferKey.hideNavigationBar, false)
            .putString(PreferKey.highlightActionTrigger, "click")
            .putBoolean(PreferKey.mouseWheelPage, true)
            .remove(PreferKey.mouseWheelScrollSpeed)
            .putInt(PreferKey.pageTouchSlop, 15)
            .commit()
    }

    @After
    fun tearDown() {
        observation?.close()
        val editor = preferences.edit()
        savedValues.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        assertTrue(editor.commit())
    }

    @Test
    fun errorStateOffersRetryAction() {
        val retryCount = AtomicInteger()
        compose.setContent {
            LegadoComposeTheme {
                MoreReaderSettingsScreen(
                    state = MoreReaderSettingsUiState(isLoading = false, error = "Read failed"),
                    visibleSettings = MoreReaderSettings.all,
                    background = Color.White,
                    slopSummary = "system default 8 px",
                    bookmarkSummary = "default 48 px",
                    onToggle = { _, _ -> },
                    onChoice = { _, _ -> },
                    onSeekBar = { _, _ -> },
                    onAction = {},
                    onRetry = { retryCount.incrementAndGet() },
                    modifier = Modifier.height(480.dp),
                )
            }
        }

        compose.onNodeWithTag("more-reader-settings-retry").performClick()
        assertEquals(1, retryCount.get())
    }

    @Test
    fun controlsPersistTypedPreferencesAndRefreshAfterExternalChanges() {
        val repository = MoreReaderSettingsRepository(context)
        val viewModel = MoreReaderSettingsViewModel(repository)
        observation = repository.observe { viewModel.refresh() }
        compose.setContent {
            LegadoComposeTheme {
                MoreReaderSettingsRoute(
                    viewModel = viewModel,
                    visibleSettings = MoreReaderSettings.all,
                    background = Color.White,
                    slopSummary = "system default 8 px",
                    bookmarkSummary = "default 48 px",
                    onToggle = viewModel::toggle,
                    onChoice = viewModel::choose,
                    onSeekBar = viewModel::setSpeed,
                    onAction = {},
                    modifier = Modifier.height(480.dp),
                )
            }
        }

        compose.waitUntil(5_000) { !viewModel.state.value.isLoading }
        compose
            .onNodeWithTag("more-reader-setting-${PreferKey.hideNavigationBar}")
            .performScrollTo()
            .performClick()
        compose.waitUntil(5_000) {
            preferences.getBoolean(PreferKey.hideNavigationBar, false)
        }

        preferences.edit().putBoolean(PreferKey.hideNavigationBar, false).commit()
        compose.waitUntil(5_000) {
            viewModel.state.value.values[PreferKey.hideNavigationBar] == "false"
        }
        compose.onNodeWithTag("more-reader-setting-${PreferKey.hideNavigationBar}").assertExists()
        compose.runOnIdle {
            assertFalse(
                viewModel.state.value.values.getValue(PreferKey.hideNavigationBar).toBoolean()
            )
        }

        compose
            .onNodeWithTag("more-reader-setting-${PreferKey.highlightActionTrigger}")
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag("more-reader-option-${PreferKey.highlightActionTrigger}-doubleTap")
            .performClick()
        compose.waitUntil(5_000) {
            preferences.getString(PreferKey.highlightActionTrigger, null) == "doubleTap"
        }
        assertEquals("doubleTap", AppConfig.highlightActionTrigger)

        compose
            .onNodeWithTag("more-reader-slider-${PreferKey.mouseWheelScrollSpeed}")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(200f)) }
        compose.waitUntil(5_000) {
            preferences.getInt(PreferKey.mouseWheelScrollSpeed, 0) == 200
        }
        assertEquals(200, AppConfig.mouseWheelScrollSpeed)
        compose
            .onNodeWithTag("more-reader-setting-${PreferKey.mouseWheelPage}")
            .performScrollTo()
            .performClick()
        compose.waitUntil(5_000) { !AppConfig.mouseWheelPage }
        compose
            .onNodeWithTag("more-reader-slider-${PreferKey.mouseWheelScrollSpeed}")
            .assertIsNotEnabled()

        var savedCallback = false
        viewModel.saveNumber(
            MoreReaderSettings.all
                .filterIsInstance<io.legado.app.data.preferences.MoreReaderSetting.Action>()
                .first { it.key == PreferKey.pageTouchSlop },
            42,
        ) {
            savedCallback = true
        }
        compose.waitUntil(5_000) {
            preferences.getInt(PreferKey.pageTouchSlop, 0) == 42 && savedCallback
        }
        assertEquals("42", viewModel.state.value.values[PreferKey.pageTouchSlop])
    }
}

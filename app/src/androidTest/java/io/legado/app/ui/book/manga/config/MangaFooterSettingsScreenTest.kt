package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.preferences.MangaFooterDraft
import io.legado.app.data.preferences.MangaFooterSettingsRepository
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MangaFooterSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allHideRowsExposeCheckboxSemanticsAndEmitMatchingFields() {
        val calls = mutableListOf<Pair<MangaFooterField, Boolean>>()
        compose.setContent {
            LegadoComposeTheme {
                MangaFooterSettingsScreen(
                    MangaFooterDraft(),
                    { field, value -> calls += field to value },
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        MangaFooterField.entries.forEach {
            compose
                .onNodeWithTag("manga-footer-${it.name}")
                .performScrollTo()
                .assertIsOff()
                .performClick()
        }
        compose.runOnIdle { assertEquals(MangaFooterField.entries.map { it to true }, calls) }
    }

    @Test
    fun footerVisibilityAndAlignmentSelectionsEmitCallbacks() {
        val visibility = mutableListOf<Boolean>()
        val orientations = mutableListOf<Int>()
        compose.setContent {
            LegadoComposeTheme {
                MangaFooterSettingsScreen(
                    MangaFooterDraft(hideFooter = true, footerOrientation = 1),
                    { _, _ -> },
                    { visibility += it },
                    { orientations += it },
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose.onNodeWithTag("manga-footer-hide").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("manga-footer-show").performScrollTo().performClick()
        compose.onNodeWithTag("manga-footer-center").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("manga-footer-left").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(false), visibility)
            assertEquals(listOf(0), orientations)
        }
    }

    @Test
    fun hiddenSelectionsRemainEditableWhenWholeFooterIsHidden() {
        var changed: Pair<MangaFooterField, Boolean>? = null
        compose.setContent {
            LegadoComposeTheme {
                MangaFooterSettingsScreen(
                    MangaFooterDraft(hideFooter = true, hideChapterName = true),
                    { field, value -> changed = field to value },
                    {},
                    {},
                    Modifier.heightIn(max = 500.dp),
                )
            }
        }
        compose
            .onNodeWithTag("manga-footer-ChapterName")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.runOnIdle { assertEquals(MangaFooterField.ChapterName to false, changed) }
    }

    @Test
    fun retainedViewModelReappliesDraftWhenHostResumesWithoutSaving() {
        val previews = mutableListOf<MangaFooterDraft>()
        val saves = mutableListOf<MangaFooterDraft>()
        lateinit var model: MangaFooterSettingsViewModel
        lateinit var owner: LifecycleOwner
        lateinit var registry: LifecycleRegistry
        compose.runOnIdle {
            owner =
                object : LifecycleOwner {
                    override val lifecycle: Lifecycle
                        get() = registry
                }
            registry = LifecycleRegistry(owner).apply { currentState = Lifecycle.State.CREATED }
            model =
                MangaFooterSettingsViewModel(
                    object : MangaFooterSettingsRepository {
                        override fun load() = MangaFooterDraft()

                        override fun preview(draft: MangaFooterDraft) {
                            previews += draft
                        }

                        override fun save(draft: MangaFooterDraft) {
                            saves += draft
                        }
                    },
                    SavedStateHandle(),
                )
            model.setFooterHidden(true)
            previews.clear()
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    MangaFooterSettingsRoute(model, Modifier.heightIn(max = 500.dp))
                }
            }
        }
        compose.runOnIdle {
            assertTrue(previews.isEmpty())
            registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { previews.size == 1 }
        compose.runOnIdle {
            assertTrue(previews.last().hideFooter)
            registry.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle { registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { previews.size == 2 }
        compose.runOnIdle {
            assertTrue(previews.last().hideFooter)
            assertTrue(saves.isEmpty())
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

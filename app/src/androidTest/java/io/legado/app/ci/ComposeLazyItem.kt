package io.legado.app.ci

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** Materialize an offscreen LazyColumn item before asserting or interacting with it. */
internal fun ComposeTestRule.lazyItem(listTag: String, itemTag: String): SemanticsNodeInteraction {
    onNodeWithTag(listTag).performScrollToNode(hasTestTag(itemTag))
    return onNodeWithTag(itemTag)
}

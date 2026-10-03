package io.legado.app.ui.main.rss

import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import org.junit.*
import org.junit.Assert.*

class MainRssIconTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Animation : ColorDrawable(Color.RED), Animatable {
        var active = false
        var starts = 0
        var stops = 0

        override fun start() {
            active = true
            starts++
        }

        override fun stop() {
            active = false
            stops++
        }

        override fun isRunning() = active
    }

    private class Images : RssArticleImageRepository {
        val requests = mutableListOf<List<Any>>()
        val resources = mutableListOf<AnimatedDrawableResource>()
        var clears = 0

        override suspend fun ratio(source: String): Float? = null

        override suspend fun load(
            source: String,
            origin: String,
            width: Int,
            height: Int,
            natural: Boolean,
        ): AnimatedDrawableResource {
            requests += listOf(source, origin, width, height, natural)
            return AnimatedDrawableResource(Animation()) { clears++ }.also { resources += it }
        }
    }

    @Test
    fun sourceOriginAndSquareCropArePreservedAndChangingSourceReleasesOnlyOldLease() {
        val images = Images()
        var row by mutableStateOf(MainRssRow("id", "origin-a", "Feed", "icon-a", false))
        var visible by mutableStateOf(true)
        compose.setContent { if (visible) MainRssIcon(row, images) }
        compose.waitUntil(timeoutMillis = 10_000) { images.requests.isNotEmpty() }
        val request = images.requests.single()
        assertEquals("icon-a", request[0])
        assertEquals("origin-a", request[1])
        assertEquals(request[2], request[3])
        assertEquals(false, request[4])
        compose.runOnIdle { row = row.copy(sourceUrl = "origin-b", icon = "icon-b") }
        compose.waitUntil(timeoutMillis = 10_000) {
            images.resources.size == 2 && images.resources.first().isReleased
        }
        assertFalse(images.resources.last().isReleased)
        assertEquals("origin-b", images.requests.last()[1])
        compose.runOnIdle { visible = false }
        compose.waitUntil(timeoutMillis = 10_000) { images.resources.last().isReleased }
        assertEquals(2, images.clears)
    }

    @Test
    fun animatedIconStopsOnInactivePagerResumesAndReleasesAtDisposal() {
        val owner = Owner()
        val images = Images()
        var visible by mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible) MainRssIcon(MainRssRow("id", "origin", "Feed", "icon", false), images)
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            images.resources.isNotEmpty() &&
                (images.resources.single().drawable as Animation).active
        }
        val drawable = images.resources.single().drawable as Animation
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle {
            assertFalse(drawable.active)
            assertFalse(images.resources.single().isReleased)
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle {
            assertTrue(drawable.active)
            visible = false
        }
        compose.waitUntil(timeoutMillis = 10_000) { images.resources.single().isReleased }
        assertFalse(drawable.active)
        assertNull(drawable.callback)
        assertEquals(1, images.clears)
    }
}

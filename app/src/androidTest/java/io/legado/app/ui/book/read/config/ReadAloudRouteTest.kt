package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.ReadAloudControlPreferences
import io.legado.app.data.preferences.ReadAloudControlRepository
import io.legado.app.data.preferences.ReadAloudControlRuntime
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadAloudRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun queuedPlatformEffectsAreDeliveredOnceInOrder() {
        lateinit var model: ReadAloudViewModel
        val delivered = mutableListOf<ReadAloudControl>()
        compose.runOnIdle { model = ReadAloudViewModel(repository(), SavedStateHandle()) }
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudRoute(
                    model,
                    Color.White,
                    Color.Black,
                    { true },
                    { delivered += it.control },
                    {},
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        compose.runOnIdle {
            model.request(ReadAloudControl.Engine)
            model.request(ReadAloudControl.Catalog)
        }
        compose.waitUntil { delivered.size == 2 }
        compose.runOnIdle {
            model.refreshRuntime()
            model.changeTimer(20)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(ReadAloudControl.Engine, ReadAloudControl.Catalog), delivered)
            assertTrue(model.state.value.pending.isEmpty())
        }
    }

    @Test
    fun unavailableHostRetainsEffectUntilPlatformDeliveryIsSafe() {
        lateinit var model: ReadAloudViewModel
        var available by mutableStateOf(false)
        val delivered = mutableListOf<ReadAloudControl>()
        compose.runOnIdle { model = ReadAloudViewModel(repository(), SavedStateHandle()) }
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudRoute(
                    model,
                    Color.White,
                    Color.Black,
                    { available },
                    { delivered += it.control },
                    {},
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        compose.runOnIdle { model.request(ReadAloudControl.Engine) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(delivered.isEmpty())
            assertEquals(1, model.state.value.pending.size)
            available = true
        }
        compose.waitForIdle()
        compose.runOnIdle { model.changeTimer(20) }
        compose.waitUntil { delivered.size == 1 }
        compose.runOnIdle {
            assertEquals(listOf(ReadAloudControl.Engine), delivered)
            assertTrue(model.state.value.pending.isEmpty())
        }
    }

    @Test
    fun restoredFinishedClosesWithoutRepeatingTheStopCommand() {
        lateinit var model: ReadAloudViewModel
        var closes = 0
        var effects = 0
        compose.runOnIdle {
            model =
                ReadAloudViewModel(repository(), SavedStateHandle(mapOf("aloud.finished" to true)))
        }
        compose.setContent {
            LegadoComposeTheme {
                ReadAloudRoute(
                    model,
                    Color.White,
                    Color.Black,
                    { true },
                    { effects++ },
                    { closes++ },
                    Modifier.heightIn(max = 700.dp),
                )
            }
        }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle {
            model.request(ReadAloudControl.Stop)
            model.refreshRuntime()
            model.changeTimer(20)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, closes)
            assertEquals(0, effects)
        }
    }

    private fun repository() =
        object : ReadAloudControlRepository {
            override fun preferences() = ReadAloudControlPreferences(false, 5, 0)

            override fun runtime() = ReadAloudControlRuntime(true, 0, 0)

            override fun saveFollowSystem(follow: Boolean) = Unit

            override fun saveRate(rate: Int) = Unit

            override fun saveDefaultTimer(minute: Int) = Unit

            override suspend fun engineName() = "Test engine"
        }
}

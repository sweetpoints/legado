package io.legado.app.help.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.DartSourceEngine
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real discovery entry point and V8, without external network dependencies. */
@RunWith(AndroidJUnit4::class)
class ExploreScriptV8IntegrationTest {
    @Test
    fun oldExportedMenusExecuteThroughActualV8WithWhitespaceAfterClosingTag() =
        runBlocking(Dispatchers.IO) {
            withTimeout(30_000) {
                for (suffix in listOf("", "\n", " \t\r\n")) {
                    val source =
                        BookSource(
                            bookSourceUrl = "https://explore-${UUID.randomUUID()}.invalid",
                            bookSourceName = "Discovery fixture",
                            exploreUrl =
                                "<js>JSON.stringify([{title:'Fixture',url:'https://fixture.invalid/list'}])</js>$suffix",
                        )
                    try {
                        val kinds = source.exploreKinds()
                        assertEquals(1, kinds.size)
                        assertEquals("Fixture", kinds.single().title)
                        assertEquals("https://fixture.invalid/list", kinds.single().url)
                    } finally {
                        source.clearExploreKindsCache()
                        DartSourceEngine.clearSourceState(source)
                    }
                }
            }
        }
}

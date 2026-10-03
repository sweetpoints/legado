package io.legado.app.base

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundImageThreadingTest {

    @Test
    fun `background image decodes off the main thread`() {
        val source = File("src/main/java/io/legado/app/base/BaseThemedActivity.kt").readText()
        val body = source.substringAfter("open fun upBackgroundImage")
        assertTrue(body.contains("Dispatchers.IO"))
        assertTrue(body.contains("isFinishing"))
    }
}

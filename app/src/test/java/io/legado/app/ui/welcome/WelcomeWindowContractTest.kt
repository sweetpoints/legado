package io.legado.app.ui.welcome

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeWindowContractTest {

    @Test
    fun `welcome startup uses an opaque window background before content draws`() {
        val activity = projectFile("src/main/java/io/legado/app/ui/welcome/WelcomeActivity.kt")
        val base = projectFile("src/main/java/io/legado/app/base/BaseThemedActivity.kt")
        val manifest = projectFile("src/main/AndroidManifest.xml")
        val styles = projectFile("src/main/res/values/styles.xml")
        assertTrue(activity.contains("override fun shouldCreateContentView()"))
        assertTrue(activity.contains("override fun shouldShowWindowBackground()"))
        assertTrue(activity.contains("FLAG_ACTIVITY_BROUGHT_TO_FRONT"))
        assertTrue(base.contains("if (!shouldCreateContentView()) return"))
        assertTrue(base.contains("Color.TRANSPARENT"))
        assertTrue(manifest.contains("android:name=\".ui.welcome.WelcomeActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/AppTheme.Welcome\""))
        assertTrue(styles.contains("android:windowBackground\">@color/background"))
    }

    // Welcome image ownership, same-file protection and deletion are exercised by
    // WelcomeSettingsRepositoryTest and AppWelcomeSettingsStoreTest against actual stores/files.
    private fun projectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp")).firstOrNull(File::isFile)?.readText()
            ?: error("Missing project file: $pathInApp")
}

package io.legado.app.ui.association

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.SourceType
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.ui.widget.dialog.PhotoDialog
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VerificationCodeComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recreationRetainsDraftAndDoesNotCancelOrDuplicateDialog() {
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        try {
            ActivityScenario.launch<VerificationCodeActivity>(intent(key)).use { scenario ->
                compose.onNodeWithTag("verification-code").performTextInput("Ab12")
                scenario.recreate()
                compose.onNodeWithTag("verification-code").assertTextContains("Ab12")
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals(1, activity.supportFragmentManager.fragments.count { it is VerificationCodeDialog })
                    assertNull(SourceVerificationHelp.getResult(key))
                }
            }
            // Closing the host is a cancellation; a recreation above must not produce this result.
            assertEquals("" to "", SourceVerificationHelp.getResult(key))
        } finally {
            SourceVerificationHelp.clearResult(key)
        }
    }

    @Test fun composeSubmissionReturnsOnlyTheMatchingRequest() {
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val other = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        try {
            ActivityScenario.launch<VerificationCodeActivity>(intent(key)).use {
                compose.onNodeWithTag("verification-code").performTextInput("1234")
                compose.onNodeWithTag("verification-submit").performClick()
                compose.waitUntil { SourceVerificationHelp.getResult(key)?.second == "1234" }
                assertEquals("" to "1234", SourceVerificationHelp.getResult(key))
                assertNull(SourceVerificationHelp.getResult(other))
            }
        } finally {
            SourceVerificationHelp.clearResult(key)
            SourceVerificationHelp.clearResult(other)
        }
    }

    @Test fun restoredPhotoKeepsAnIndependentPreviewFile() {
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        try {
            ActivityScenario.launch<VerificationCodeActivity>(intent(key)).use { scenario ->
                compose.waitUntil(10_000) {
                    val node = compose.onNodeWithTag("verification-image").fetchSemanticsNode()
                    !node.config.contains(SemanticsProperties.Disabled)
                }
                compose.onNodeWithTag("verification-image").assertIsEnabled().performClick()
                var previewSrc: String? = null
                scenario.onActivity { activity ->
                    val verification = activity.supportFragmentManager.fragments.filterIsInstance<VerificationCodeDialog>().single()
                    val photo = verification.childFragmentManager.fragments.filterIsInstance<PhotoDialog>().single()
                    previewSrc = photo.requireArguments().getString("src")
                    assertTrue(File(requireNotNull(previewSrc)).isFile)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val verification = activity.supportFragmentManager.fragments.filterIsInstance<VerificationCodeDialog>().single()
                    val photo = verification.childFragmentManager.fragments.filterIsInstance<PhotoDialog>().single()
                    assertEquals(previewSrc, photo.requireArguments().getString("src"))
                    val file = File(requireNotNull(previewSrc))
                    assertTrue(file.isFile)
                    assertTrue(file.length() > 0)
                    assertNull(SourceVerificationHelp.getResult(key))
                }
            }
        } finally {
            SourceVerificationHelp.clearResult(key)
        }
    }

    private fun intent(key: String) = Intent(context, VerificationCodeActivity::class.java).apply {
        putExtra("verificationResultKey", key)
        putExtra("sourceOrigin", "test-source")
        putExtra("sourceName", "Test")
        putExtra("sourceType", SourceType.book)
        putExtra("imageUrl", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")
    }
}

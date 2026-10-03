package io.legado.app.ui.association

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.SourceType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenUrlConfirmActivityTest {
    @Test
    fun recreatedHostRetainsOneDialogAndDoesNotFinish() {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), OpenUrlConfirmActivity::class.java)
                .putExtra("uri", "legado://target")
                .putExtra("mimeType", "application/pdf")
                .putExtra("sourceName", "My source")
                .putExtra("sourceOrigin", "source-id")
                .putExtra("sourceType", SourceType.rss)
        ActivityScenario.launch<OpenUrlConfirmActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertEquals(
                    1,
                    activity.supportFragmentManager.fragments
                        .filterIsInstance<OpenUrlConfirmDialog>()
                        .size,
                )
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertFalse(activity.isFinishing)
                val dialogs =
                    activity.supportFragmentManager.fragments.filterIsInstance<
                        OpenUrlConfirmDialog
                    >()
                assertEquals(1, dialogs.size)
                assertEquals("legado://target", dialogs.single().arguments?.getString("uri"))
                assertEquals("application/pdf", dialogs.single().arguments?.getString("mimeType"))
                assertEquals(SourceType.rss, dialogs.single().arguments?.getInt("sourceType"))
            }
        }
    }

    @Test
    fun dismissingTheDialogNormallyFinishesItsTransparentHost() {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), OpenUrlConfirmActivity::class.java)
                .putExtra("uri", "legado://target")
        ActivityScenario.launch<OpenUrlConfirmActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                activity.supportFragmentManager.fragments
                    .filterIsInstance<OpenUrlConfirmDialog>()
                    .single()
                    .dismissNow()
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test
    fun outgoingIntentKeepsDataAndMimeTypeTogether() {
        val intent = createOpenUrlIntent("https://example.com/document", "application/pdf")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/document", intent.dataString)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun outgoingIntentOmitsBlankMimeType() {
        val intent = createOpenUrlIntent("legado://target", " ")
        assertEquals("legado://target", intent.dataString)
        assertNull(intent.type)
    }
}

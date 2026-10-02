package io.legado.app.ui.main.bookshelf.style1

import android.content.Intent
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.bookshelf.style1.books.BooksFragment
import org.junit.Assert.*
import org.junit.Test

class BookshelfLegacyPageRestoreTest {
    @Test fun restoredPagerChildrenAreRemovedWithoutRemovingOtherChildren() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ConfigActivity>(Intent(context, ConfigActivity::class.java)
            .putExtra("configTag", ConfigTag.COVER_FONT_CONFIG)).use { scenario ->
            scenario.onActivity { activity ->
                val parent = Fragment()
                activity.supportFragmentManager.beginTransaction().add(parent, "legacy-page-owner")
                    .setMaxLifecycle(parent, Lifecycle.State.CREATED).commitNow()
                val legacy = BooksFragment()
                val preserved = DialogFragment()
                parent.childFragmentManager.beginTransaction()
                    .add(0x01020304, legacy, "android:switcher:legacy:0")
                    .add(preserved, "restored-popup")
                    .setMaxLifecycle(legacy, Lifecycle.State.CREATED)
                    .setMaxLifecycle(preserved, Lifecycle.State.CREATED).commitNow()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val parent = activity.supportFragmentManager.findFragmentByTag("legacy-page-owner")!!
                val manager = parent.childFragmentManager
                assertTrue(manager.fragments.any { it is BooksFragment })
                val popup = manager.findFragmentByTag("restored-popup")
                removeLegacyBookshelfPages(manager)
                assertFalse(manager.fragments.any { it is BooksFragment })
                assertSame(popup, manager.findFragmentByTag("restored-popup"))
                removeLegacyBookshelfPages(manager)
                assertSame(popup, manager.findFragmentByTag("restored-popup"))
                activity.supportFragmentManager.beginTransaction().remove(parent).commitNow()
            }
        }
    }
}

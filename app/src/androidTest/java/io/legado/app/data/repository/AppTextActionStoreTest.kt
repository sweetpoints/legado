package io.legado.app.data.repository

import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppTextActionStoreTest {
    @Test
    fun realPackageManagerDiscoveryKeepsQueryOrderComponentsAndLabelsAsPlainValues() =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = AppTextActionStore(context)
            val expected =
                if (Build.VERSION.SDK_INT < 23) emptyList()
                else
                    context.packageManager
                        .queryIntentActivities(
                            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"),
                            0,
                        )
                        .map { info ->
                            TextProcessTarget(
                                info.activityInfo.packageName,
                                info.activityInfo.name,
                                info.loadLabel(context.packageManager).toString(),
                            )
                        }
            assertEquals(expected, store.processTargets())
            assertEquals(9, store.titles().size)
            assertEquals(
                context.getString(android.R.string.copy),
                store.titles()[TextActionKind.Copy],
            )
        }
}

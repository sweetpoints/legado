package io.legado.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadMangaCallbackOwnershipTest {
    private class Callback : ReadManga.Callback {
        override fun upContent() = Unit

        override fun loadFail(msg: String, retry: Boolean) = Unit

        override fun sureNewProgress(progress: BookProgress) = Unit

        override fun showLoading() = Unit

        override fun startLoad() = Unit
    }

    @Test
    fun obsoleteCallbackTeardownLeavesReplacementJobsRunning() = runBlocking {
        val savedCallback = ReadManga.mCallback
        val oldOwner = Callback()
        val replacement = Callback()
        val engineStarted = CompletableDeferred<Unit>()
        val downloadStarted = CompletableDeferred<Unit>()
        ReadManga.register(oldOwner)
        ReadManga.register(replacement)
        val engineJob =
            ReadManga.launch(Dispatchers.IO) {
                engineStarted.complete(Unit)
                awaitCancellation()
            }
        val downloadJob =
            ReadManga.downloadScope.launch {
                downloadStarted.complete(Unit)
                awaitCancellation()
            }
        try {
            engineStarted.await()
            downloadStarted.await()
            ReadManga.unregister(oldOwner)
            assertSame(replacement, ReadManga.mCallback)
            assertTrue(engineJob.isActive)
            assertTrue(downloadJob.isActive)
            ReadManga.unregister(replacement)
            engineJob.join()
            downloadJob.join()
            assertTrue(engineJob.isCancelled)
            assertTrue(downloadJob.isCancelled)
        } finally {
            engineJob.cancelAndJoin()
            downloadJob.cancelAndJoin()
            if (savedCallback != null) ReadManga.register(savedCallback)
        }
    }
}

package io.legado.app.ui.book.source

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.book.source.manage.AppBookSourceManagerRepository
import io.legado.app.ui.book.source.manage.BookSourceManagerRepository
import io.legado.app.ui.book.source.manage.BookSourceManagerViewModel
import io.legado.app.ui.book.source.manage.SourceManagerPreferences
import io.legado.app.ui.book.source.manage.SourceManagerSession
import io.legado.app.ui.book.source.manage.SourceManagerSessionStorage
import io.legado.app.ui.book.source.manage.dispatchSourceManagerEffects
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourceManagerLifecycleTest {
    @Test
    fun pausedOwnerRejectsLateIoAndResumedOwnerLaunchesPendingRequestOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preparationStarted = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        val resolveCount = AtomicInteger()
        val repository =
            object : BookSourceManagerRepository by AppBookSourceManagerRepository(context) {
                override suspend fun resolve(keys: List<String>): List<BookSourcePart> {
                    resolveCount.incrementAndGet()
                    preparationStarted.complete(Unit)
                    // Model a Room read which returns after the lifecycle cancels its collector.
                    withContext(Dispatchers.IO + NonCancellable) { releasePreparation.await() }
                    return keys.map { BookSourcePart(bookSourceUrl = it) }
                }
            }
        val storage = MemoryStorage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val launches = AtomicInteger()
        val models = ViewModelStore()
        lateinit var model: BookSourceManagerViewModel
        lateinit var owner: Owner
        try {
            withContext(Dispatchers.Main) {
                owner = Owner()
                owner.registry.currentState = Lifecycle.State.RESUMED
                model = BookSourceManagerViewModel(repository, storage, Preferences())
                models.put("manager", model)
                scope.launch {
                    dispatchSourceManagerEffects(model, owner.lifecycle, { true }) {
                        launches.incrementAndGet()
                    }
                }
            }
            awaitCondition { !model.state.value.loading }
            withContext(Dispatchers.Main) { model.effect("search", "late-source") }
            withTimeout(5_000) { preparationStarted.await() }
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
            releasePreparation.complete(Unit)
            awaitCondition { resolveCount.get() == 1 }
            delay(100)
            assertEquals(0, launches.get())
            assertNotNull(model.state.value.effect)
            assertFalse(storage.snapshot.receipts.contains(model.state.value.effect!!.id))
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.RESUMED }
            awaitCondition { launches.get() == 1 }
            withContext(Dispatchers.Main) {
                owner.registry.currentState = Lifecycle.State.STARTED
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            delay(100)
            assertEquals(1, launches.get())
            assertEquals(2, resolveCount.get())
        } finally {
            releasePreparation.complete(Unit)
            withContext(Dispatchers.Main) {
                scope.cancel()
                models.clear()
            }
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Preferences : SourceManagerPreferences {
        override var showStatus = false
        override var blockNavigation = false
    }

    private class MemoryStorage : SourceManagerSessionStorage {
        @Volatile var snapshot = SourceManagerSession()

        override fun read() = snapshot

        override fun write(session: SourceManagerSession) {
            snapshot = session
        }

        override fun delete() = Unit
    }
}

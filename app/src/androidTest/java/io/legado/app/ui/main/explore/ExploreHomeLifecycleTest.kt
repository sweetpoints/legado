package io.legado.app.ui.main.explore

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.BookSourcePart
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
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExploreHomeLifecycleTest {
    @Test
    fun pausedOwnerRejectsLateSearchAndResumedOwnerDeliversItOnce() = runBlocking {
        val prepared = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val launches = AtomicInteger()
        val reads = AtomicInteger()
        val repository =
            object : ExploreHomeRepository by AppExploreHomeRepository() {
                override suspend fun searchSource(url: String): BookSourcePart {
                    reads.incrementAndGet()
                    prepared.complete(Unit)
                    withContext(Dispatchers.IO + NonCancellable) { releaseRead.await() }
                    return BookSourcePart(bookSourceUrl = url)
                }
            }
        val storage = Storage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val models = ViewModelStore()
        lateinit var model: ExploreHomeViewModel
        lateinit var owner: Owner
        try {
            withContext(Dispatchers.Main) {
                owner = Owner()
                owner.registry.currentState = Lifecycle.State.RESUMED
                model = ExploreHomeViewModel(repository, storage)
                models.put("explore", model)
                scope.launch {
                    dispatchExploreHomeEffects(
                        model,
                        owner.lifecycle,
                        { true },
                        { launches.incrementAndGet() },
                    )
                }
            }
            withTimeout(5_000) { while (model.state.value.loading) delay(10) }
            withContext(Dispatchers.Main) { model.effect("search", "prepared-source") }
            withTimeout(5_000) { prepared.await() }
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.STARTED }
            releaseRead.complete(Unit)
            delay(100)
            assertEquals(0, launches.get())
            assertNotNull(model.state.value.effect)
            withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.RESUMED }
            withTimeout(5_000) { while (launches.get() == 0) delay(10) }
            assertEquals(1, launches.get())
            assertEquals(2, reads.get())
        } finally {
            releaseRead.complete(Unit)
            withContext(Dispatchers.Main) {
                scope.cancel()
                models.clear()
            }
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Storage : ExploreHomeSessionStorage {
        @Volatile private var snapshot = ExploreHomeSession()

        override fun read() = snapshot

        override fun write(snapshot: ExploreHomeSession): Boolean {
            this.snapshot = snapshot
            return true
        }

        override fun delete() = Unit
    }
}

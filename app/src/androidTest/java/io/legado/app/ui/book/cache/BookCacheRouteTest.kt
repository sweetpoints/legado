package io.legado.app.ui.book.cache

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookCacheRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<BookCacheViewModel>(); private val repos = mutableListOf<BookCacheTestRepository>()
    @After fun after() { compose.runOnIdle { models.forEach { it.stop(); it.viewModelScope.cancel() }; repos.forEach { it.stageGate?.complete(Unit) } } }
    private fun model(repo: BookCacheTestRepository, saved: SavedStateHandle = SavedStateHandle()): BookCacheViewModel { lateinit var result: BookCacheViewModel; compose.runOnIdle { result = BookCacheViewModel(repo, saved); models += result; repos += repo }; return result }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle get() = registry }
    @Test fun delayedStageWhilePausedDeliversFolderOnceAfterResumeAndNeverExportsUntilResult() {
        val repo = BookCacheTestRepository(); repo.stageGate = CompletableDeferred(); val vm = model(repo); val owner = Owner(); var folders = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { BookCacheRoute(vm, {}, { folders++ }, {}, { error(it) }) } } }
        compose.waitUntil { vm.state.value.preferencesLoaded && !vm.state.value.loading }; compose.runOnIdle { vm.export("remote") }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; repo.stageGate!!.complete(Unit) }
        compose.waitUntil { vm.state.value.folder != null }; assertEquals(0, folders); assertTrue(repo.exports.isEmpty())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { folders == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1, folders)
        compose.runOnIdle { vm.folderResult("content://folder") }; compose.waitUntil { repo.exports.size == 1 }; assertEquals(listOf("remote"), repo.exports.single().keys)
    }
    @Test fun failedNativeFolderLaunchReleasesReceiptAndDoesNotAutomaticallyRetry() {
        val repo = BookCacheTestRepository(); val vm = model(repo); var launches = 0; var errors = 0
        compose.setContent { LegadoComposeTheme { BookCacheRoute(vm, {}, { launches++; error("no picker") }, {}, { errors++ }) } }
        compose.waitUntil { vm.state.value.preferencesLoaded && !vm.state.value.loading }; compose.runOnIdle { vm.export("remote") }
        compose.waitUntil { errors == 1 && repo.tickets.isEmpty() }; compose.runOnIdle { vm.refresh() }; compose.waitForIdle()
        assertEquals(1, launches); assertTrue(repo.exports.isEmpty()); assertNull(vm.state.value.folder)
    }
    @Test fun finishedRestoreOnlyClosesAtResumeWithoutDeliveringFolderOrServicesAgain() {
        val repo = BookCacheTestRepository(); val vm = model(repo, SavedStateHandle(mapOf("cache.closed" to true))); val owner = Owner(); var closes = 0; var folders = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { BookCacheRoute(vm, { closes++ }, { folders++ }, {}, { error(it) }) } } }
        compose.waitForIdle(); assertEquals(0, closes); compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle()
        assertEquals(1, closes); assertEquals(0, folders); assertTrue(repo.exports.isEmpty())
    }
}

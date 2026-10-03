package io.legado.app.data.repository

import io.legado.app.help.TextSelectMenuConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TextActionRepositoryTest {
    @Test
    fun defaultConfigKeepsFivePrimaryAndRemainingBuiltinsBeforeProcessApplications() = runBlocking {
        val repo = DefaultTextActionRepository(Fake())
        val snapshot = repo.load()
        assertEquals(TextSelectMenuConfig.DEFAULT_BAR, snapshot.primary.map { it.kind.configKey })
        assertEquals(
            listOf("dict", "search", "browser", "share", "processText", "processText"),
            snapshot.more.map { it.kind.configKey },
        )
        assertEquals(
            listOf("External A", "External B"),
            snapshot.more.filter { it.process != null }.map { it.title },
        )
        assertTrue((snapshot.primary + snapshot.more).map { it.id }.distinct().size == 11)
    }

    @Test
    fun configuredProcessGroupCanLeadPrimaryAndDuplicatesUnknownKeysDoNotDuplicateActions() =
        runBlocking {
            val store =
                Fake().apply {
                    config =
                        TextSelectMenuConfig(
                            listOf("processText", "copy", "copy", "unknown"),
                            listOf("share", "processText"),
                        )
                }
            val snapshot = DefaultTextActionRepository(store).load()
            assertEquals(
                listOf("External A", "External B", "Copy"),
                snapshot.primary.map { it.title },
            )
            assertEquals("Share", snapshot.more.first().title)
            assertEquals(11, (snapshot.primary + snapshot.more).size)
            assertEquals(11, (snapshot.primary + snapshot.more).map { it.id }.distinct().size)
        }

    @Test
    fun expandedLegacyConfigHasNoMoreWhenAllBuiltinAndProcessActionsFitPrimary() = runBlocking {
        val store = Fake().apply { config = TextSelectMenuConfig.migrateFrom(true) }
        val snapshot = DefaultTextActionRepository(store).load()
        assertEquals(11, snapshot.primary.size)
        assertTrue(snapshot.more.isEmpty())
    }

    @Test
    fun missingProcessAppsLeaveNoPlaceholderAndFailurePreservesBuiltinsWithSeparateError() =
        runBlocking {
            val store = Fake().apply { targets = emptyList() }
            val repo = DefaultTextActionRepository(store)
            assertEquals(9, repo.load().let { it.primary.size + it.more.size })
            store.discoveryFails = true
            val failed = repo.load()
            assertEquals(9, failed.primary.size + failed.more.size)
            assertEquals("discovery", failed.discoveryError)
        }

    @Test
    fun refreshUsesLatestConfigurationAndProcessLabelsAndKeepsSameBuiltInIdentity() = runBlocking {
        val store = Fake()
        val repo = DefaultTextActionRepository(store)
        val initial = repo.load()
        val copyId = initial.primary.first { it.kind == TextActionKind.Copy }.id
        store.config = TextSelectMenuConfig(listOf("copy"), emptyList())
        store.targets = listOf(TextProcessTarget("new", "Component", "New app"))
        val current = repo.load()
        assertEquals(listOf(copyId), current.primary.map { it.id })
        assertEquals("New app", current.more.last().title)
    }

    @Test
    fun discoveryConfigurationAndSerializedSpeakTogglesRunOnInjectedIoThread() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "text-action-io") }
            .asCoroutineDispatcher()
            .use { dispatcher ->
                val store = Fake()
                val repo = DefaultTextActionRepository(store, dispatcher)
                repo.load()
                assertEquals(1, repo.toggleSpeakMode())
                assertEquals(0, repo.toggleSpeakMode())
                store.mode = 99
                assertEquals(0, repo.toggleSpeakMode())
                val initialThread = store.threads.first()
                assertTrue(store.threads.all { it === initialThread })
                assertFalse(initialThread === Thread.currentThread())
                coroutineScope {
                    listOf(async { repo.toggleSpeakMode() }, async { repo.toggleSpeakMode() })
                        .awaitAll()
                }
                assertEquals(0, store.mode)
            }
    }

    @Test
    fun processComponentIdentitySurvivesPackageManagerReorderingAndDuplicateComponentsRemainDistinct() =
        runBlocking {
            val store = Fake()
            val repo = DefaultTextActionRepository(store)
            val before =
                repo
                    .load()
                    .more
                    .filter { it.process != null }
                    .associate { it.process!!.className to it.id }
            store.targets = store.targets.reversed()
            val after =
                repo
                    .load()
                    .more
                    .filter { it.process != null }
                    .associate { it.process!!.className to it.id }
            assertEquals(before, after)
            store.targets = store.targets + store.targets.first()
            val duplicate = repo.load().more.filter { it.process != null }
            assertEquals(3, duplicate.map { it.id }.distinct().size)
            assertEquals(
                listOf("External B", "External A", "External B"),
                duplicate.map { it.title },
            )
        }

    private class Fake : TextActionStore {
        var config = TextSelectMenuConfig.default()
        var mode = 0
        var discoveryFails = false
        var targets =
            listOf(
                TextProcessTarget("a", "ActivityA", "External A"),
                TextProcessTarget("b", "ActivityB", "External B"),
            )
        val threads = mutableListOf<Thread>()

        private fun record() {
            threads += Thread.currentThread()
        }

        override suspend fun config(): TextSelectMenuConfig {
            record()
            return config
        }

        override suspend fun titles(): Map<TextActionKind, String> {
            record()
            return TextActionKind.entries.associateWith { it.name }
        }

        override suspend fun processTargets(): List<TextProcessTarget> {
            record()
            if (discoveryFails) error("discovery")
            return targets
        }

        override suspend fun speakMode(): Int {
            record()
            return mode
        }

        override suspend fun setSpeakMode(value: Int) {
            record()
            mode = value
        }
    }
}

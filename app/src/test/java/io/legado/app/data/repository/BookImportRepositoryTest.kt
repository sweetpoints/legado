package io.legado.app.data.repository

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookImportRepositoryTest {
    private fun source(url: String = "https://feed.invalid", time: Long = 4) =
        BookSource(
            bookSourceUrl = url,
            bookSourceName = "Original",
            bookSourceGroup = "A;B",
            bookSourceComment = "comment",
            lastUpdateTime = time,
            loginUrl = "login",
            header = "header",
            jsLib = "library",
            enabledCookieJar = true,
            loginCheckJs = "preload",
            exploreScreen = "html",
            searchUrl = "next",
            eventListener = true,
            customOrder = 9,
        )

    private fun rule(id: Long, replacement: String) =
        ReplaceRule(
            id = id,
            name = "Replace",
            pattern = "Original",
            replacement = replacement,
            isRegex = false,
            scopeSource = true,
        )

    private fun originals(vararg values: BookSource) = values.mapIndexed { index, value ->
        BookImportOriginal("key-$index", GSON.toJson(value))
    }

    private fun decode(json: String) = GSON.fromJsonObject<BookSource>(json).getOrThrow()

    private fun test(block: suspend (DefaultBookImportRepository, Fake) -> Unit) {
        var ioThread: Thread? = null
        Executors.newSingleThreadExecutor { task ->
                Thread(task, "book-import-io").also { ioThread = it }
            }
            .asCoroutineDispatcher()
            .use { io ->
                val store = Fake()
                val repo = DefaultBookImportRepository(store, io)
                runBlocking { block(repo, store) }
                assertTrue(store.threads.all { it === ioThread })
            }
    }

    @Test
    fun objectArrayUriAndSourceUrlsEnvelopeUseTheActualParserWithoutDuplicateAppending() =
        test { repo, store ->
            val first = source()
            val second = source("https://second.invalid")
            assertEquals(GSON.toJson(first), repo.load(GSON.toJson(first)).single().json)
            assertEquals(2, repo.load(GSON.toJson(listOf(first, second))).size)
            store.uri = GSON.toJson(listOf(first))
            assertEquals(1, repo.load("content://test/source").size)
            store.urls["https://remote.invalid/list"] = listOf(first, second)
            assertEquals(2, repo.load("""{"sourceUrls":["https://remote.invalid/list"]}""").size)
            assertEquals(listOf("https://remote.invalid/list"), store.requests)
            assertEquals(
                listOf("0", "1"),
                repo.load(GSON.toJson(listOf(first, second))).map { it.key },
            )
        }

    @Test
    fun invalidSourceUrlsBlankSourceAndMultiSourceEditFailWithoutWriting() = test { repo, store ->
        listOf(
                """{"sourceUrls":null}""",
                """{"sourceUrls":[""]}""",
                """{"bookSourceUrl":""}""",
                "invalid",
            )
            .forEach {
                assertTrue(runCatching { repo.load(it) }.isFailure)
            }
        assertTrue(
            runCatching { repo.parseEdited("stable", GSON.toJson(listOf(source(), source()))) }
                .isFailure
        )
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun selectedAutomaticRulesChangeOnlyDerivedJsonAndRecordEffectiveIds() = test { repo, store ->
        store.rules =
            listOf(
                rule(1, "Changed"),
                rule(2, "Unused").copy(pattern = "not present"),
                rule(3, "Excluded").copy(excludeScope = "Original"),
            )
        val entry = repo.refresh(originals(source()), true, emptyMap()).single()
        assertEquals("Changed", entry.sourceName)
        assertEquals(listOf(1L), entry.effectiveRuleIds)
        assertEquals("Original", decode(entry.originalJson).bookSourceName)
        assertEquals(entry.json, entry.replacedJson)
        assertTrue(entry.selectedByDefault)
    }

    @Test
    fun manualRulesArePerStableKeyAndSwitchingOffRestoresOriginalSources() = test { repo, store ->
        store.rules = listOf(rule(1, "One"), rule(2, "Two"))
        val raw = originals(source(), source("https://other.invalid"))
        val entries = repo.refresh(raw, false, mapOf("key-1" to listOf(2L)))
        assertEquals(listOf("Original", "Two"), entries.map { it.sourceName })
        assertEquals(listOf(2L), entries[1].effectiveRuleIds)
        assertEquals(
            listOf("Original", "Original"),
            repo.refresh(raw, false, emptyMap()).map { it.sourceName },
        )
    }

    @Test
    fun invalidReplacementDisablesOnlyAffectedSourceAndNeverWritesIt() = test { repo, store ->
        store.rules = listOf(rule(1, "\"").copy(scope = "Original"))
        val raw = originals(source(), source("https://other.invalid").copy(bookSourceName = "Safe"))
        val entries = repo.refresh(raw, true, emptyMap())
        assertEquals(BookImportStatus.Error, entries.first().status)
        assertFalse(entries.first().canImport)
        assertNotNull(entries.first().replacementError)
        assertFalse(entries.first().selectedByDefault)
        assertTrue(entries.last().canImport)
        repo.insert(
            "session",
            BookImportSnapshot(entries, true, emptyMap()),
            setOf("key-0", "key-1"),
            BookImportPreferences(),
            null,
            false,
        )
        assertEquals(listOf("Safe"), store.inserted.map { it.bookSourceName })
    }

    @Test
    fun comparedUrlUsesTransformedPayloadAndUpdatedTimestampDeterminesDefaultSelection() =
        test { repo, store ->
            val incoming = source()
            store.local = listOf(incoming.copy(bookSourceName = "Stored", lastUpdateTime = 3))
            assertEquals(
                BookImportStatus.Update,
                repo.refresh(originals(incoming), false, emptyMap()).single().status,
            )
            store.local = listOf(incoming.copy(lastUpdateTime = 4))
            val existing = repo.refresh(originals(incoming), false, emptyMap()).single()
            assertEquals(BookImportStatus.Existing, existing.status)
            assertFalse(existing.selectedByDefault)
            store.rules =
                listOf(
                    rule(1, "Changed")
                        .copy(pattern = "https://feed.invalid", replacement = "https://new.invalid")
                )
            val transformed = repo.refresh(originals(incoming), true, emptyMap()).single()
            assertEquals(BookImportStatus.New, transformed.status)
            assertEquals("https://new.invalid", transformed.sourceUrl)
            assertEquals(listOf("https://new.invalid"), store.comparedUrls.last())
        }

    @Test
    fun importKeepsOriginalMetadataThenAppliesExplicitGroupWithoutMutatingStagedJson() =
        test { repo, store ->
            val current =
                source()
                    .copy(
                        bookSourceName = "Incoming",
                        bookSourceGroup = "Incoming",
                        enabled = true,
                        customOrder = 44,
                    )
            val stored =
                current.copy(
                    bookSourceName = "Stored",
                    bookSourceGroup = "A;B",
                    enabled = false,
                    customOrder = 7,
                    lastUpdateTime = 1,
                )
            store.local = listOf(stored)
            val entries = repo.refresh(originals(current), false, emptyMap())
            val snapshot = BookImportSnapshot(entries, false, emptyMap())
            repo.stage("session", snapshot)
            repo.insert(
                "session",
                snapshot,
                setOf("key-0"),
                BookImportPreferences(keepName = true, keepGroup = true, keepEnable = true),
                " B ",
                true,
            )
            val imported = store.inserted.single()
            assertEquals("Stored", imported.bookSourceName)
            assertEquals("A,B", imported.bookSourceGroup)
            assertFalse(imported.enabled)
            assertEquals(7, imported.customOrder)
            assertEquals(current.header, imported.header)
            assertEquals(current.jsLib, imported.jsLib)
            assertEquals(current.loginCheckJs, imported.loginCheckJs)
            assertEquals(current.exploreScreen, imported.exploreScreen)
            assertEquals(GSON.toJson(current), entries.single().json)
            assertTrue(repo.restore("session")!!.committed)
            assertEquals(entries, repo.restore("session")!!.items)
        }

    @Test
    fun replacementModeSnapshotAndEditedKeyRoundTripWithoutImporting() = test { repo, store ->
        val original = originals(source()).single()
        val edited =
            repo.parseEdited(
                original.key,
                GSON.toJson(source("https://changed.invalid").copy(bookSourceName = "Edited")),
            )
        assertEquals(original.key, edited.key)
        val entries = repo.refresh(listOf(edited), false, mapOf(edited.key to emptyList()))
        val snapshot = BookImportSnapshot(entries, false, mapOf(edited.key to listOf(4L, 5L)))
        repo.stage("session", snapshot)
        assertEquals(snapshot, repo.restore("session"))
        assertTrue(store.inserted.isEmpty())
    }

    @Test
    fun preferencesGroupsAndFailuresUseTheIoBoundaryAndFailedInsertDoesNotCommit() =
        test { repo, store ->
            val prefs =
                BookImportPreferences(
                    keepName = true,
                    showComment = true,
                    rememberGroup = true,
                    lastGroup = "Group",
                    lastGroupAdd = true,
                )
            repo.preferences(prefs)
            assertEquals(prefs, repo.preferences())
            assertEquals(listOf("A", "B"), repo.groups())
            assertTrue(store.threads.isNotEmpty())
            val snapshot =
                BookImportSnapshot(
                    repo.refresh(originals(source()), false, emptyMap()),
                    false,
                    emptyMap(),
                )
            repo.stage("session", snapshot)
            store.insertFailure = true
            assertTrue(
                runCatching {
                        repo.insert("session", snapshot, setOf("key-0"), prefs, "New", false)
                    }
                    .isFailure
            )
            assertFalse(repo.restore("session")!!.committed)
        }

    @Test
    fun directJavaScriptAndCancellationUseIoBoundaryWithoutWrappingCancellation() =
        test { repo, store ->
            val original =
                repo
                    .load(
                        "function search() {} function getChapters() {} function getContent() {} "
                    )
                    .single()
            assertEquals("https://js.invalid", decode(original.json).bookSourceUrl)
            assertNotNull(decode(original.json).mainJs)
            val failure = runCatching { repo.load("cancel") }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
        }

    @Test
    fun explorerEnableAndJavaScriptLoginFormAreRetainedAndComparedWithoutMutableUiEntities() =
        test { repo, store ->
            val incoming =
                source()
                    .copy(
                        enabledExplore = true,
                        mainJs = "functions",
                        loginUrl = null,
                        loginUi = "[{\"name\":\"Username\"}]",
                    )
            val entry = repo.refresh(originals(incoming), false, emptyMap()).single()
            assertTrue(entry.enabledExplore)
            assertTrue(entry.needsLogin)
            store.local =
                listOf(incoming.copy(enabled = false, enabledExplore = false, customOrder = 8))
            val snapshot =
                BookImportSnapshot(
                    repo.refresh(originals(incoming), false, emptyMap()),
                    false,
                    emptyMap(),
                )
            repo.insert(
                "session",
                snapshot,
                setOf("key-0"),
                BookImportPreferences(keepEnable = true),
                null,
                false,
            )
            assertFalse(store.inserted.single().enabled)
            assertFalse(store.inserted.single().enabledExplore)
            assertEquals(8, store.inserted.single().customOrder)
        }

    private class Fake : BookImportStore {
        val threads = mutableListOf<Thread>()

        private fun touched() {
            threads += Thread.currentThread()
        }

        var uri = ""
        val urls = mutableMapOf<String, List<BookSource>>()
        val requests = mutableListOf<String>()
        var rules = emptyList<ReplaceRule>()
        var local = emptyList<BookSource>()
        var prefs = BookImportPreferences()
        val comparedUrls = mutableListOf<List<String>>()
        val inserted = mutableListOf<BookSource>()
        var insertFailure = false
        val sessions = mutableMapOf<String, String>()

        override suspend fun uriText(uri: String): String {
            touched()
            return this.uri
        }

        override suspend fun urlSources(url: String): List<BookSource> {
            touched()
            requests += url
            return urls[url] ?: error("missing url")
        }

        override suspend fun javascriptSource(text: String): BookSource {
            touched()
            if (text == "cancel") throw kotlinx.coroutines.CancellationException("cancelled")
            require(text.startsWith("function")) { "format" }
            return sourceForJs.copy(mainJs = text)
        }

        var sourceForJs =
            BookSource(bookSourceUrl = "https://js.invalid", bookSourceName = "JavaScript")

        override suspend fun sourceRules(): List<ReplaceRule> {
            touched()
            return rules
        }

        override suspend fun existing(urls: List<String>): List<BookSource> {
            touched()
            comparedUrls += urls
            return local.filter { it.bookSourceUrl in urls }
        }

        override suspend fun groups(): List<String> {
            touched()
            return listOf("A", "B")
        }

        override suspend fun preferences(): BookImportPreferences {
            touched()
            return prefs
        }

        override suspend fun preferences(value: BookImportPreferences) {
            touched()
            prefs = value
        }

        override suspend fun insert(sources: List<BookSource>) {
            touched()
            if (insertFailure) error("disk")
            inserted += sources
        }

        override suspend fun readSession(session: String): String? {
            touched()
            return sessions[session]
        }

        override suspend fun writeSession(session: String, json: String) {
            touched()
            sessions[session] = json
        }
    }
}

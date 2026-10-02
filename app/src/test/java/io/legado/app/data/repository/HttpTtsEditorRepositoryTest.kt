package io.legado.app.data.repository

import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class HttpTtsEditorRepositoryTest {
    @Test fun changedScriptClearsOldScopeBeforeInsertAndConcurrencyAfterInsert() = runTest {
        val order = mutableListOf<String>(); val dao = FakeDao(HttpTTS(id = 7, name = "name", url = "url", jsLib = "old"), order)
        val repo = AppHttpTtsEditorRepository(dao, { order += "scope:$it" }, { order += "global" }, { order += "concurrent:$it" }, { "7" })
        val original = repo.load(7)!!
        assertTrue(repo.save(original, original.copy(jsLib = "new")))
        assertEquals(listOf("scope:old", "insert", "concurrent:httpTts:7"), order)
        assertEquals("new", dao.value.jsLib)
    }
    @Test fun unchangedScriptSkipsScopeClearAndRetainsCookieAndPause() = runTest {
        val order = mutableListOf<String>(); val dao = FakeDao(HttpTTS(id = 7, name = "name", url = "url", jsLib = "same"), order)
        val repo = AppHttpTtsEditorRepository(dao, { order += "scope" }, { order += "global" }, { order += "concurrent" }, { "other" })
        val original = repo.load(7)!!
        assertFalse(repo.save(original, original.copy(pause = "20000", cookie = true)))
        assertEquals(listOf("insert", "concurrent"), order); assertEquals(10000, dao.value.pauseDuration); assertTrue(dao.value.enabledCookieJar == true)
    }
    @Test fun changedEngineKeyClearsOldGlobalStateWithSameScript() = runTest {
        val order = mutableListOf<String>(); val dao = FakeDao(HttpTTS(id = 7, name = "name", url = "url", jsLib = "same"), order)
        val repo = AppHttpTtsEditorRepository(dao, { order += "scope" }, { order += "global:${it.id}" }, { order += "concurrent:$it" }, { "8" })
        val original = repo.load(7)!!
        assertTrue(repo.save(original, original.copy(id = 8)))
        assertEquals(listOf("global:7", "insert", "concurrent:httpTts:8"), order)
    }
    @Test fun clipboardObjectOrFirstArrayEntryUseCurrentStableEditorId() = runTest {
        val repo = AppHttpTtsEditorRepository(FakeDao(HttpTTS(), mutableListOf()), {}, {}, {}, { null })
        val objectText = """{"id":999,"name":"Imported","url":"url","jsLib":"lib","enabledCookieJar":true}"""
        assertEquals(7L, repo.parse(objectText, 7).id)
        val first = repo.parse("[$objectText,{\"name\":\"second\",\"url\":\"url\"}]", 7)
        assertEquals("Imported", first.name); assertEquals("lib", first.jsLib); assertTrue(first.cookie)
    }
    private class FakeDao(var value: HttpTTS, private val order: MutableList<String>) : HttpTTSDao {
        override val all get() = listOf(value)
        override val count get() = 1
        override fun flowAll() = flowOf(all)
        override fun get(id: Long) = value.takeIf { it.id == id }
        override fun getName(id: Long) = get(id)?.name
        override fun insert(vararg httpTTS: HttpTTS) { order += "insert"; value = httpTTS.single() }
        override fun delete(vararg httpTTS: HttpTTS) = Unit
        override fun update(vararg httpTTS: HttpTTS) = Unit
        override fun deleteDefault() = Unit
    }
}

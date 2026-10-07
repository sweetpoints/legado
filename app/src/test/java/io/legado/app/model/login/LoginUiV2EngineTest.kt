package io.legado.app.model.login

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import org.junit.Assert.*
import org.junit.Test

/** Pure JSON contracts; script execution is covered by FlutterLoginUiV2Test on Android. */
class LoginUiV2EngineTest {
    @Test
    fun rendersExplicitJsonStateResults() {
        val first =
            LoginUiV2.parseRender(
                """{"rows":[{"key":"phone","name":"手机号","type":"text"},{"name":"发送验证码","type":"button","action":"sendCode"}]}"""
            )
        assertEquals("phone", first!![0].key)
        val second =
            LoginUiV2.parseRender(
                """{"rows":[{"key":"code","name":"验证码","type":"text"},{"name":"重新发码","type":"button","action":"sendCode","countdown":60}]}"""
            )
        assertEquals("code", second!![0].key)
        assertEquals(60, second[1].countdown)
    }

    @Test
    fun actionObjectsStringsAndNullKeepJsonBoundary() {
        val state =
            LoginUiV2.parseActionResult(
                LoginUiV2Script.jsonResult(
                    mapOf("state" to mapOf("step" to "code", "phone" to "+8613800000000"))
                )
            )
        assertTrue(state.stateJson!!.contains("+8613800000000"))
        assertNull(state.error)
        val error = LoginUiV2.parseActionResult("""{"error":{"phone":"手机号必填"}}""")
        assertEquals("手机号必填", error.error!!["phone"])
        val neutral = LoginUiV2.parseActionResult(LoginUiV2Script.jsonResult(null))
        assertNull(neutral.stateJson)
        assertFalse(neutral.close)
        assertEquals("plain", LoginUiV2Script.jsonResult("plain"))
    }

    @Test
    fun declarativeLoginScriptSelectionStillUsesLoginUrl() {
        val script = "async function loginUi(state){return {rows:[]};}"
        val declarative =
            BookSource(
                bookSourceUrl = "https://declarative.example.com",
                loginUi = LoginUiV2.MARKER,
                loginUrl = script,
            )
        assertEquals(script, declarative.getLoginJs())
        val result = LoginUiV2.parseActionResult("""{"login":{"account":"reader"},"close":true}""")
        assertEquals("""{"account":"reader"}""", result.loginJson)
        assertTrue(result.close)
    }

    @Test
    fun loginBindingsContainJsonBookAndChapterRatherThanNativeObjects() {
        val book = Book(bookUrl = "book://context")
        val chapter = BookChapter(bookUrl = book.bookUrl, index = 7)
        val bindings =
            LoginUiV2Script.bindings("{}", book, chapter, "submit", "{\"value\":\"reader\"}")
        assertEquals("book://context", (bindings["book"] as Map<*, *>)["bookUrl"])
        assertEquals(7, ((bindings["chapter"] as Map<*, *>)["index"] as Number).toInt())
        assertEquals("submit", bindings["__loginAction"])
        assertEquals("{}", bindings["__loginState"])
        assertTrue(bindings["book"] !is Book)
        assertTrue(bindings["chapter"] !is BookChapter)
    }
}

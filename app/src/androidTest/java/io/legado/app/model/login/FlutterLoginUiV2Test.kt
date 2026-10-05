package io.legado.app.model.login

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.BuildConfig
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.AppSourceLoginFormRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlutterLoginUiV2Test {

    @Before
    fun requireFlutterEngine() {
        assumeTrue(BuildConfig.FLUTTER_SOURCE_ENGINE)
    }

    private val source =
        BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "登录测试",
            loginUi = LoginUiV2.MARKER,
            mainJs =
                """
                async function loginUi(state) {
                    if (!state.step) return { rows: [
                        { key: "phone", name: "手机号", type: "text" },
                        { name: "发送验证码", type: "button", action: "sendCode" }
                    ] };
                    return { rows: [
                        { key: "code", name: "验证码", type: "text" },
                        { name: "重新发码", type: "button", action: "sendCode", countdown: 60 }
                    ] };
                }
                async function loginAction(action, state, form) {
                    if (action == "sendCode") {
                        if (!form.phone) return { error: { phone: "手机号必填" } };
                        return { state: { step: "code", phone: `+86${'$'}{form.phone}` } };
                    }
                    if (action == "noop") return;
                    return { login: { token: state.phone + "-tk" }, close: true };
                }
                """
                    .trimIndent(),
        )

    @Test
    fun rendersFromExplicitState() = runBlocking {
        withTimeout(60_000) {
            withContext(Dispatchers.IO) {
                val first = LoginUiV2.parseRender(source.evalLoginUiV2("{}"))
                assertEquals("phone", first!![0].key)

                val second = LoginUiV2.parseRender(source.evalLoginUiV2("""{"step":"code"}"""))
                assertEquals("code", second!![0].key)
                assertEquals(60, second[1].countdown)
            }
        }
    }

    @Test
    fun dispatchesActionsAcrossJsonBoundary() = runBlocking {
        withTimeout(60_000) {
            withContext(Dispatchers.IO) {
                val state =
                    LoginUiV2.parseActionResult(
                        source.evalLoginActionV2(
                            "sendCode",
                            "{}",
                            """{"phone":"13800000000"}""",
                        )
                    )
                assertTrue(state.stateJson!!.contains("+8613800000000"))
                assertNull(state.error)

                val error =
                    LoginUiV2.parseActionResult(source.evalLoginActionV2("sendCode", "{}", "{}"))
                assertEquals("手机号必填", error.error!!["phone"])

                val neutral =
                    LoginUiV2.parseActionResult(source.evalLoginActionV2("noop", "{}", "{}"))
                assertNull(neutral.stateJson)
                assertFalse(neutral.close)
            }
        }
    }

    @Test
    fun declarativeLoginScriptUsesLoginUrl() = runBlocking {
        withTimeout(60_000) {
            withContext(Dispatchers.IO) {
                val declarative =
                    BookSource(
                        bookSourceUrl = "https://declarative.example.com",
                        bookSourceName = "声明式登录测试",
                        loginUi = LoginUiV2.MARKER,
                        loginUrl =
                            """
                            async function loginUi(state) {
                                return { rows: [{ key: "account", name: "账号", type: "text" }] };
                            }
                            async function loginAction(action, state, form) {
                                return { login: { account: form.account }, close: true };
                            }
                            """
                                .trimIndent(),
                    )

                val rows = LoginUiV2.parseRender(declarative.evalLoginUiV2("{}"))
                assertEquals("account", rows!![0].key)
                val result =
                    LoginUiV2.parseActionResult(
                        declarative.evalLoginActionV2("submit", "{}", """{"account":"reader"}""")
                    )
                assertEquals("""{"account":"reader"}""", result.loginJson)
                assertTrue(result.close)
            }
        }
    }

    @Test
    fun v2LoginScriptReceivesBookAndChapterContext() = runBlocking {
        withTimeout(60_000) {
            withContext(Dispatchers.IO) {
                val source =
                    BookSource(
                        bookSourceUrl = "https://context.example.com",
                        bookSourceName = "上下文登录测试",
                        loginUi = LoginUiV2.MARKER,
                        loginUrl =
                            """
                            async function loginUi(state) {
                                return { rows: [{ name: book.bookUrl + ':' + chapter.index, type: 'label' }] };
                            }
                            async function loginAction(action, state, form) {
                                return { login: { context: book.bookUrl + ':' + chapter.index }, close: true };
                            }
                            """
                                .trimIndent(),
                    )
                val book = Book(bookUrl = "book://context")
                val chapter = BookChapter(bookUrl = book.bookUrl, index = 7)

                val rows = LoginUiV2.parseRender(source.evalLoginUiV2("{}", book, chapter))
                assertEquals("book://context:7", rows!![0].name)

                val result =
                    LoginUiV2.parseActionResult(
                        source.evalLoginActionV2("submit", "{}", "{}", book, chapter)
                    )
                assertEquals("""{"context":"book://context:7"}""", result.loginJson)
                assertTrue(result.close)
            }
        }
    }

    @Test
    fun repositoryAwaitsAsyncScriptsAndKeepsRowsImmutable() = runBlocking {
        withTimeout(60_000) {
            withContext(Dispatchers.Main.immediate) {
                val delegate =
                    BookSource(
                        bookSourceUrl = "https://repository-login.example.com",
                        bookSourceName = "Source",
                        loginUi = LoginUiV2.MARKER,
                        mainJs =
                            """
                            async function loginUi(state) { await Promise.resolve(); return {rows:[
                                {name:'Phone',key:'phone',type:'text',hint:'Required',value:state.phone},
                                {name:'Send',type:'button',action:'send',countdown:30}
                            ]}; }
                            async function loginAction(action,state,form) {
                                await Promise.resolve();
                                if (!form.phone) return {error:{phone:'required'}};
                                return {state:{phone:form.phone},login:{token:'ok'}};
                            }
                            """
                                .trimIndent(),
                    )
                val source =
                    object : BaseSource by delegate {
                        override fun getLoginInfoMap() = mutableMapOf("phone" to "stored")
                    }
                val repo = AppSourceLoginFormRepository(source, null, null, emptyMap())
                val initial = repo.render(emptyMap(), "{}")
                assertEquals("stored", initial.stored["phone"])
                assertEquals("Required", initial.rows.first().hint)
                assertEquals(30, initial.rows.last().countdown)
                assertTrue(initial.rows.first().modern)
                assertEquals("required", repo.action("send", "{}", emptyMap()).error?.get("phone"))
                val command = repo.action("send", "{}", mapOf("phone" to "123"))
                assertTrue(command.loginJson != null)
                val next = repo.render(emptyMap(), command.stateJson!!)
                assertEquals("123", next.rows.first().value)
                assertNull(initial.rows.first().value)
            }
        }
    }

    @Test
    fun synchronousV2EvaluationRejectsMainThread() = runBlocking {
        val failure =
            withContext(Dispatchers.Main.immediate) { runCatching { source.evalLoginUiV2("{}") } }
        assertTrue(
            failure.exceptionOrNull()?.message.orEmpty().contains("engine_migration_required")
        )
    }
}

package io.legado.app.data.repository

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.model.login.LoginUiV2
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceLoginFormRepositoryTest {
    @Test
    fun parsedV2ResultsKeepJsonBoundaryAndImmutableRows() = runTest {
        val delegate =
            BookSource(
                bookSourceUrl = "https://example.com",
                bookSourceName = "Source",
                loginUi = LoginUiV2.MARKER,
                mainJs =
                    """
                    function loginUi(state) { return {rows:[
                        {name:'Phone',key:'phone',type:'text',hint:'Required',value:state.phone},
                        {name:'Send',type:'button',action:'send',countdown:30}
                    ]}; }
                    function loginAction(action,state,form) {
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
        val caller = Thread.currentThread().threadId()
        var worker = caller
        val repo =
            AppSourceLoginFormRepository(source, null, null, emptyMap()) { _, _, bindings ->
                worker = Thread.currentThread().threadId()
                if (bindings.containsKey("__loginAction")) {
                    val form =
                        com.google.gson.JsonParser.parseString(bindings["__loginForm"] as String)
                            .asJsonObject
                    val phone = form.get("phone")?.asString
                    if (phone == null) mapOf("error" to mapOf("phone" to "required"))
                    else
                        mapOf("state" to mapOf("phone" to phone), "login" to mapOf("token" to "ok"))
                } else {
                    val state =
                        com.google.gson.JsonParser.parseString(bindings["__loginState"] as String)
                            .asJsonObject
                    mapOf(
                        "rows" to
                            listOf(
                                mapOf(
                                    "name" to "Phone",
                                    "key" to "phone",
                                    "type" to "text",
                                    "hint" to "Required",
                                    "value" to state.get("phone")?.asString,
                                ),
                                mapOf(
                                    "name" to "Send",
                                    "type" to "button",
                                    "action" to "send",
                                    "countdown" to 30,
                                ),
                            )
                    )
                }
            }
        val initial = repo.render(emptyMap(), "{}")
        assertEquals("stored", initial.stored["phone"])
        assertEquals("Required", initial.rows.first().hint)
        assertEquals(30, initial.rows.last().countdown)
        assertTrue(initial.rows.first().modern)
        val error = repo.action("send", "{}", emptyMap())
        assertEquals("required", error.error?.get("phone"))
        val command = repo.action("send", "{}", mapOf("phone" to "123"))
        assertNotNull(command.loginJson)
        val next = repo.render(emptyMap(), command.stateJson!!)
        assertEquals("123", next.rows.first().value)
        assertNull(initial.rows.first().value)
        assertNotEquals(caller, worker)
    }

    @Test
    fun clearRemovesLoginInfoThenHeaderAndCookieViaSourceContractOnIo() = runTest {
        val operations = mutableListOf<String>()
        val caller = Thread.currentThread().threadId()
        var worker = caller
        val delegate = BookSource(bookSourceUrl = "https://example.com", bookSourceName = "Source")
        val source =
            object : BaseSource by delegate {
                override fun removeLoginInfo() {
                    worker = Thread.currentThread().threadId()
                    operations += "info"
                }

                override fun removeLoginHeader() {
                    operations += "header-cookie"
                }
            }
        AppSourceLoginFormRepository(source, null, null, emptyMap()).clear()
        assertEquals(listOf("info", "header-cookie"), operations)
        assertNotEquals(caller, worker)
    }
}

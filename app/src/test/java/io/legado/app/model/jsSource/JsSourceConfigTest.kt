package io.legado.app.model.jsSource

import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.login.LoginUiV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM verifies configuration contracts; actual V8 execution belongs to device acceptance. */
class JsSourceConfigTest {
    private val base =
        mapOf<String, Any?>("bookSourceUrl" to "https://example.com", "bookSourceName" to "示例源")
    private val required = JsSourceConfig.requiredFunctions.associateWith { "function" }

    private fun materialize(
        config: Any? = base,
        functions: Map<String, String> = required,
        legacy: Any? = null,
    ): BookSource =
        JsSourceConfig.materializeRuntimeConfig(
            mapOf("config" to config, "legacyConfig" to legacy, "functions" to functions),
            "original script",
        )

    private fun error(part: String, action: () -> Unit) {
        val failure = assertThrows(NoStackTraceException::class.java, action)
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(part))
    }

    @Test
    fun configurationPrecedenceAndSourceMetadataArePreserved() {
        assertEquals("示例源", materialize().bookSourceName)
        assertEquals("original script", materialize().mainJs)
        assertEquals("示例源", materialize(legacy = base + ("bookSourceName" to "旧版")).bookSourceName)
        assertEquals(
            "旧版",
            materialize(
                    config = mapOf("unrelated" to true),
                    legacy = base + ("bookSourceName" to "旧版"),
                )
                .bookSourceName,
        )
        assertEquals("示例源", materialize(config = null, legacy = base).bookSourceName)
        error("缺少顶层") { materialize(config = null) }
        error("不是合法对象") { materialize(config = listOf(1)) }
        error("bookSourceUrl 不能为空") { materialize(base + ("bookSourceUrl" to "")) }
        error("bookSourceName 不能为空") { materialize(base + ("bookSourceName" to "")) }
    }

    @Test
    fun requiredFunctionsAndFileSourceContractsRemainDistinct() {
        for (name in JsSourceConfig.requiredFunctions) {
            error("必备函数 $name") { materialize(functions = required - name) }
            error("必备函数 $name") { materialize(functions = required + (name to "string")) }
        }
        val file = base + ("bookSourceType" to BookSourceType.file)
        assertEquals(
            BookSourceType.file,
            materialize(file, mapOf("search" to "function", "getBookInfo" to "function"))
                .bookSourceType,
        )
        error("getBookInfo") { materialize(file, required) }
        error("search") { materialize(file, mapOf("getBookInfo" to "function")) }
    }

    @Test
    fun declarativeRulesAreStrippedAndUpdateTimeRemainsMetadata() {
        val source =
            materialize(
                base +
                    mapOf(
                        "mainJs" to "injected",
                        "ruleSearch" to mapOf("name" to "x"),
                        "ruleContent" to mapOf("content" to "x"),
                        "lastUpdateTime" to 1752449000000L,
                    )
            )
        assertNull(source.ruleSearch)
        assertNull(source.ruleContent)
        assertEquals("original script", source.mainJs)
        assertEquals(1752449000000L, source.lastUpdateTime)
        assertEquals(0L, materialize().lastUpdateTime)
    }

    @Test
    fun exploreAndLoginArraysRetainNormalizationAndMatchingFunctionValidation() {
        val explore = base + ("exploreUrl" to listOf(mapOf("title" to "分类", "url" to "/kind")))
        error("explore 函数") { materialize(explore) }
        assertTrue(
            materialize(explore, required + ("explore" to "function"))
                .exploreUrl
                .orEmpty()
                .startsWith("[")
        )
        error("缺少 title") { materialize(base + ("exploreUrl" to listOf(mapOf("url" to "/kind")))) }
        val login = base + ("loginUi" to listOf(mapOf("name" to "username", "type" to "text")))
        error("login 函数") { materialize(login) }
        assertTrue(
            materialize(login, required + ("login" to "function")).loginUi.orEmpty().startsWith("[")
        )
        error("缺少 name") { materialize(base + ("loginUi" to listOf(mapOf("type" to "text")))) }
        assertNull(materialize(base + ("loginUi" to emptyList<Any>())).loginUi)
        assertNull(materialize(base + ("loginUi" to " [ ] ")).loginUi)
        assertEquals(
            "https://login.example",
            materialize(base + ("loginUrl" to "https://login.example")).loginUrl,
        )
    }

    @Test
    fun loginUiFunctionRequiresActionAndCannotConflictWithConfiguredData() {
        error("loginAction") { materialize(functions = required + ("loginUi" to "function")) }
        val functions = required + mapOf("loginUi" to "function", "loginAction" to "function")
        assertEquals(LoginUiV2.MARKER, materialize(functions = functions).loginUi)
        error("二选一") {
            materialize(base + ("loginUi" to listOf(mapOf("name" to "username"))), functions)
        }
    }

    @Test
    fun reviewPropertiesRequireFunctionTypesAndPairing() {
        for (name in listOf("getReviewSummary", "getReviewDetail", "getReviewReplies")) {
            error("必须是函数") { materialize(functions = required + (name to "number")) }
        }
        error("getReviewDetail") {
            materialize(functions = required + ("getReviewSummary" to "function"))
        }
        error("getReviewSummary") {
            materialize(functions = required + ("getReviewDetail" to "function"))
        }
        error("getReviewSummary/getReviewDetail") {
            materialize(functions = required + ("getReviewReplies" to "function"))
        }
        materialize(
            functions =
                required +
                    listOf("getReviewSummary", "getReviewDetail", "getReviewReplies")
                        .associateWith { "function" }
        )
    }

    @Test
    fun batchSizeRequiresExactNumericIntegerAndMatchingBatchFunction() {
        for (value in listOf<Any?>(2.5, 2147483648L, 4294967298L, 1e100, "2", null, true)) {
            error("必须是整数") {
                materialize(
                    base + ("maxBatchSize" to value),
                    required + ("getContentBatch" to "function"),
                )
            }
        }
        error("必须大于1") { materialize(base + ("maxBatchSize" to 1)) }
        error("getContentBatch") { materialize(base + ("maxBatchSize" to 2)) }
        error("maxBatchSize") {
            materialize(functions = required + ("getContentBatch" to "function"))
        }
        assertEquals(
            2,
            materialize(
                    base + ("maxBatchSize" to 2.0),
                    required + ("getContentBatch" to "function"),
                )
                .contentBatchSize(),
        )
        assertEquals(
            BookSource.MAX_CONTENT_BATCH_SIZE,
            materialize(
                    base + ("maxBatchSize" to Int.MAX_VALUE),
                    required + ("getContentBatch" to "function"),
                )
                .contentBatchSize(),
        )
    }

    @Test
    fun `review capability ignores comments and requires both functions`() {
        assertFalse(
            JsSourceConfig.declaresReviewFunctions(
                "/* function getReviewSummary() {} function getReviewDetail() {} */"
            )
        )
        assertFalse(JsSourceConfig.declaresReviewFunctions("function getReviewSummary() {}"))
        assertTrue(
            JsSourceConfig.declaresReviewFunctions(
                "function getReviewSummary() {} function getReviewDetail() {}"
            )
        )
        assertTrue(
            JsSourceConfig.declaresReviewFunctions(
                "var getReviewSummary = function() {}; " + "var getReviewDetail = function() {};"
            )
        )
    }

    @Test
    fun `review reply capability ignores comments and accepts a top level function`() {
        assertFalse(
            JsSourceConfig.declaresReviewRepliesFunction("/* function getReviewReplies() {} */")
        )
        assertTrue(JsSourceConfig.declaresReviewRepliesFunction("function getReviewReplies() {}"))
        assertTrue(
            JsSourceConfig.declaresReviewRepliesFunction("var getReviewReplies = function() {};")
        )
    }

    @Test
    fun `stamps declared update time`() {
        val script = "var config = { lastUpdateTime: Date.now() };"
        assertEquals(
            "var config = { lastUpdateTime: 123456 };",
            JsSourceConfig.stampLastUpdateTime(script, 123456),
        )
    }

    @Test
    fun `stamps numeric update time without touching later declarations`() {
        val script =
            """
            var source = {
                lastUpdateTime: 0 // version timestamp
            };
            var fallback = { lastUpdateTime: 1 };
            """
                .trimIndent()
        val expected =
            """
            var source = {
                lastUpdateTime: 123456 // version timestamp
            };
            var fallback = { lastUpdateTime: 1 };
            """
                .trimIndent()

        assertEquals(expected, JsSourceConfig.stampLastUpdateTime(script, 123456))
    }

    @Test
    fun `ignores comments and unrelated objects before declared update time`() {
        val script =
            """
            // lastUpdateTime: 1
            var metadata = { lastUpdateTime: 2 };
            var config = { "lastUpdateTime": Date.now() };
            """
                .trimIndent()
        val expected =
            """
            // lastUpdateTime: 1
            var metadata = { lastUpdateTime: 2 };
            var config = { "lastUpdateTime": 123456 };
            """
                .trimIndent()

        assertEquals(expected, JsSourceConfig.stampLastUpdateTime(script, 123456))
    }
}

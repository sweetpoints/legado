package io.legado.app.data.repository

import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SourceLoginRow(
    val name: String,
    val type: String,
    val key: String = name,
    val label: String = name,
    val labelScript: String? = null,
    val action: String? = null,
    val default: String? = null,
    val value: String? = null,
    val options: List<String> = emptyList(),
    val style: FlexChildStyle = FlexChildStyle(),
    val countdown: Int? = null,
    val hint: String? = null,
    val baseLabel: String = label,
    val modern: Boolean = false,
)

data class SourceLoginRendered(
    val rows: List<SourceLoginRow>,
    val stored: Map<String, String> = emptyMap(),
)

data class SourceLoginDefinition(
    val title: String,
    val v2: Boolean,
    val values: Map<String, String>,
)

interface SourceLoginFormRepository {
    val definition: SourceLoginDefinition

    suspend fun ready(): SourceLoginDefinition = definition

    fun retry() = Unit

    suspend fun render(values: Map<String, String>, stateJson: String): SourceLoginRendered

    suspend fun label(script: String, values: Map<String, String>): String?

    suspend fun legacyAction(script: String, values: Map<String, String>, long: Boolean, java: Any)

    suspend fun legacyLogin(values: Map<String, String>, java: Any): Boolean

    suspend fun action(
        action: String,
        stateJson: String,
        values: Map<String, String>,
    ): LoginUiV2.ActionResult

    suspend fun store(json: String): Boolean

    suspend fun persist(values: Map<String, String>)

    suspend fun header(): String?

    suspend fun deleteHeader()

    suspend fun clear()
}

class AppSourceLoginFormRepository(
    private val source: BaseSource,
    private val book: Book?,
    private val chapter: BookChapter?,
    values: Map<String, String>,
) : SourceLoginFormRepository {
    override val definition =
        SourceLoginDefinition(source.getTag(), source.isLoginUiV2(), values.toMap())

    private fun uiRow(row: RowUi): SourceLoginRow {
        val literal =
            row.viewName?.takeIf { it.length in 3..19 && it.first() == '\'' && it.last() == '\'' }
        val label =
            if (definition.v2) row.name else literal?.substring(1, literal.length - 1) ?: row.name
        val options =
            if (definition.v2) row.options.orEmpty()
            else
                row.chars?.filterNotNull()
                    ?: if (row.type == RowUi.Type.select) listOf("chars", "is null")
                    else listOf("chars is null")
        return SourceLoginRow(
            row.name,
            row.type,
            if (definition.v2) row.key ?: row.name else row.name,
            label,
            if (!definition.v2 && row.viewName != null && literal == null) row.viewName else null,
            row.action,
            row.default,
            if (definition.v2) row.value else null,
            options.toList(),
            row.style().copy(),
            row.countdown,
            row.hint,
            modern = definition.v2,
        )
    }

    private suspend fun eval(
        script: String,
        values: Map<String, String>,
        java: Any? = null,
        long: Boolean = false,
    ): String? = runScriptWithContext {
        JsSourceEngine.normalizeJsResult(
            source.evalJS("${source.getLoginJs().orEmpty()}\n$script") {
                put("result", values.toMutableMap())
                put("book", book)
                put("chapter", chapter)
                if (java != null) {
                    put("java", java)
                    put("isLongClick", long)
                }
            }
        )
    }

    override suspend fun render(values: Map<String, String>, stateJson: String) =
        withContext(Dispatchers.IO) {
            runScriptWithContext {
                if (definition.v2) {
                    val rows =
                        LoginUiV2.parseRender(source.evalLoginUiV2(stateJson, book, chapter))
                            ?: error("登录UI v2 渲染结果格式错误")
                    SourceLoginRendered(rows.map(::uiRow), source.getLoginInfoMap().toMap())
                } else {
                    val text = source.getLoginUiJs()?.let { eval(it, values) } ?: source.loginUi
                    val rows = GSON.fromJsonArray<RowUi>(text).getOrThrow()
                    SourceLoginRendered(rows.map(::uiRow))
                }
            }
        }

    override suspend fun label(script: String, values: Map<String, String>) =
        withContext(Dispatchers.IO) { eval(script, values) }

    override suspend fun legacyAction(
        script: String,
        values: Map<String, String>,
        long: Boolean,
        java: Any,
    ) =
        withContext(Dispatchers.IO) {
            try {
                eval(script, values, java, long)
                Unit
            } catch (error: Exception) {
                AppLog.put("LoginUI Button JavaScript error", error)
                throw error
            }
        }

    override suspend fun legacyLogin(values: Map<String, String>, java: Any) =
        withContext(Dispatchers.IO) {
            if (values.isEmpty()) {
                source.removeLoginInfo()
                true
            } else if (!source.putLoginInfo(GSON.toJson(values))) false
            else {
                if (!source.getLoginJs().isNullOrBlank())
                    eval(
                        "if (typeof login=='function'){ login.apply(this); } else { throw('Function login not implements!!!') }",
                        values,
                        java,
                    )
                true
            }
        }

    override suspend fun action(action: String, stateJson: String, values: Map<String, String>) =
        withContext(Dispatchers.IO) {
            runScriptWithContext {
                LoginUiV2.parseActionResult(
                        source.evalLoginActionV2(
                            action,
                            stateJson,
                            GSON.toJson(values),
                            book,
                            chapter,
                        )
                    )
                    .also { result ->
                        result.unknownKeys.forEach {
                            AppLog.put("登录UI v2 动作 $action 返回未知命令 $it,已忽略")
                        }
                    }
            }
        }

    override suspend fun store(json: String) =
        withContext(Dispatchers.IO) { source.putLoginInfo(json) }

    override suspend fun persist(values: Map<String, String>) =
        withContext(Dispatchers.IO) {
            if (values.isEmpty()) source.removeLoginInfo()
            else source.putLoginInfo(GSON.toJson(values))
            Unit
        }

    override suspend fun header() = withContext(Dispatchers.IO) { source.getLoginHeader() }

    override suspend fun deleteHeader() = withContext(Dispatchers.IO) { source.removeLoginHeader() }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            source.removeLoginInfo()
            source.removeLoginHeader()
        }
}

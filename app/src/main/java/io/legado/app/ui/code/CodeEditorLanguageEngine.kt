package io.legado.app.ui.code

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.sourceEngine.V8ScriptExecutor
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource
import org.jsoup.Jsoup
import splitties.init.appCtx

/** TextMate, rule formatting and V8 syntax checks without Activity state. */
internal class CodeEditorLanguageEngine(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val beautifyJs by lazy {
        appCtx.assets.open("scripts/beautify.min.js").bufferedReader().use { it.readText() }
    }
    private val themeFileNames =
        arrayOf(
            "d_monokai_dimmed",
            "d_monokai",
            "d_modern",
            "l_modern",
            "d_solarized",
            "l_solarized",
            "d_abyss",
            "l_quiet",
        )
    private val themeRegistry = ThemeRegistry.getInstance()
    private var languageName = "source.js"
    private var language: RuntimeObjectCompletionLanguage? = null
    private var editorOwnsLanguage = false
    private var themeIndex = -1

    suspend fun prepare(session: CodeEditorSession): RuntimeObjectCompletionLanguage =
        withContext(Dispatchers.IO) {
            registryMutex.withLock {
                if (!initialized) {
                    FileProviderRegistry.getInstance()
                        .addFileProvider(AssetsFileResolver(appCtx.assets))
                    GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
                    initialized = true
                }
                val dark = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
                themeIndex = if (dark) AppConfig.editThemeDark else AppConfig.editTheme
                loadTextMateThemes(themeIndex)
                languageName = session.languageName
                RuntimeObjectCompletionLanguage(
                        TextMateLanguage.create(languageName, AppConfig.editAutoComplete)
                    )
                    .also { language = it }
            }
        }

    fun attachLanguage(): RuntimeObjectCompletionLanguage {
        editorOwnsLanguage = true
        return checkNotNull(language)
    }

    fun theme(index: Int, editor: CodeEditor, active: () -> Boolean) {
        if (themeIndex == index) return
        scope.launch {
            withContext(Dispatchers.IO) { registryMutex.withLock { loadTextMateThemes(index) } }
            if (active()) {
                editor.setEditorLanguage(language)
                themeIndex = index
            }
        }
    }

    private fun loadTextMateThemes(index: Int) {
        val theme = themeFileNames.getOrElse(index) { "d_monokai" }
        val themeModel = themeRegistry.findThemeByFileName(theme)
        if (themeModel == null) {
            val themeAssetsPath = "textmate/$theme.json"
            val themeSource =
                IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(themeAssetsPath),
                    themeAssetsPath,
                    null,
                )
            themeRegistry.loadTheme(
                ThemeModel(themeSource, theme).apply {
                    isDark = theme.startsWith("d_")
                }
            )
        } else {
            themeRegistry.setTheme(themeModel)
        }
    }

    fun formatCode(editor: CodeEditor, active: () -> Boolean) {
        val source = editor.text.toString()
        scope.launch {
            try {
                val formatted =
                    withContext(Dispatchers.IO) {
                        val text = source
                        if (languageName.contains("markdown")) {
                            context.toastOnUi("markdown不需要格式化")
                            return@withContext text
                        }
                        val isHtml = languageName.contains("html")
                        if (isHtml) {
                            return@withContext formatCodeHtml(text)
                        }
                        formatRuleExpression(text, ::webFormatCode)?.let {
                            return@withContext it
                        }
                        var result = ""
                        var start = 0
                        val indexS = text.indexOf("<js>")
                        if (indexS >= 0) {
                            if (indexS > 0) {
                                result += text.substring(start, indexS).trim()
                            }
                            val indexE = text.indexOf("</js>", indexS)
                            val jsCode = text.substring(indexS + 4, indexE)
                            result += "<js>\n"
                            result += webFormatCode(jsCode)
                            result += "\n</js>"
                            start = indexE + 5
                        }
                        val indexS2 = text.indexOf("@js:")
                        if (indexS2 >= 0) {
                            if (indexS2 > start) {
                                result += text.substring(start, indexS2).trim()
                            }
                            val jsCode = text.substring(indexS2 + 4)
                            result += "@js:\n"
                            result += webFormatCode(jsCode)
                            start = text.length
                        } else {
                            val indexS2 = text.indexOf("@webjs:")
                            if (indexS2 >= 0) {
                                if (indexS2 > start) {
                                    result += text.substring(start, indexS2).trim()
                                }
                                val jsCode = text.substring(indexS2 + 7)
                                result += "@webjs:\n"
                                result += webFormatCode(jsCode)
                                start = text.length
                            }
                        }
                        if (start == 0) {
                            result += webFormatCode(text)
                            start = text.length
                        }
                        if (text.length > start) {
                            result += text.substring(start).trim()
                        }
                        result
                    }
                if (
                    active() &&
                        formatted != null &&
                        formatted != source &&
                        editor.text.toString() == source
                ) {
                    editor.text.replace(0, editor.text.length, formatted)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (active()) AppLog.put("格式化失败", error, true)
            }
        }
    }

    fun checkJavaScriptSyntax(editor: CodeEditor, active: () -> Boolean) {
        val source = editor.text.toString()
        scope.launch {
            try {
                val diagnostic =
                    withContext(Dispatchers.IO) { V8ScriptExecutor.checkSyntax(source) }
                if (diagnostic != null) {
                    if (!active() || editor.text.toString() != source) return@launch
                    if (diagnostic.lineNumber > 0) {
                        val index =
                            scriptSourceIndex(
                                source,
                                diagnostic.lineNumber,
                                diagnostic.columnNumber,
                            )
                        val position = editor.cursor.indexer.getCharPosition(index)
                        editor.setSelection(position.line, position.column, true)
                        editor.requestFocus()
                    }
                    AppLog.put(diagnostic.message, toast = true)
                    return@launch
                }
                if (active() && editor.text.toString() == source)
                    context.toastOnUi(R.string.javascript_syntax_correct)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!active() || editor.text.toString() != source) return@launch
                AppLog.put(
                    error.localizedMessage ?: context.getString(R.string.javascript_syntax_error),
                    error,
                    true,
                )
            }
        }
    }

    fun dispose() {
        scope.cancel()
        if (!editorOwnsLanguage) language?.destroy()
        language = null
    }

    private suspend fun webFormatCode(jsCode: String): String =
        V8ScriptExecutor.evaluateString(
            """
                var window = globalThis;
                $beautifyJs
                js_beautify(formatterInput, {
                    indent_size: 4, indent_char: ' ', preserve_newlines: true,
                    max_preserve_newlines: 5, brace_style: 'collapse',
                    space_before_conditional: true, unescape_strings: false,
                    jslint_happy: false, end_with_newline: false,
                    wrap_line_length: 0, comma_first: false
                });
            """
                .trimIndent(),
            mapOf("formatterInput" to jsCode),
            timeoutMillis = 5_000,
        )

    private fun formatCodeHtml(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.outputSettings().indentAmount(4).prettyPrint(true)
        return doc.outerHtml()
    }

    private companion object {
        val registryMutex = Mutex()
        var initialized = false
    }
}

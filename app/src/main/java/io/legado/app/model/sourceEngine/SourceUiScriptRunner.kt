package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.shouldSuppressSourceNavigation
import io.legado.app.ui.rss.read.RssJsExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** UI scripts retain Android capabilities through explicit task callbacks, never Java objects. */
object SourceUiScriptRunner {
    suspend fun evaluate(
        source: BaseSource,
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        extensions: RssJsExtensions,
    ): Any? {
        val context = currentCoroutineContext()
        val callbacks = SourceHostCallbacks { method, args ->
            context.ensureActive()
            fun text(index: Int): String =
                args.getOrNull(index) as? String
                    ?: error("$method requires a string at argument $index")
            fun optional(index: Int): String? =
                args.getOrNull(index)?.let {
                    it as? String ?: error("$method requires a nullable string at argument $index")
                }
            val navigation =
                method in
                    setOf(
                        "ui.searchBook",
                        "ui.addBook",
                        "ui.showPhoto",
                        "ui.open",
                    ) || method.startsWith("browser.")
            if (
                navigation &&
                    shouldSuppressSourceNavigation(AppConfig.blockSourceNavigation, context)
            ) {
                error("Source navigation is suppressed for this operation")
            }
            when (method) {
                "ui.get" -> extensions.get(text(0))
                "ui.put" -> extensions.put(text(0), text(1))
                "ui.getString" ->
                    extensions.analyzeRule.withScriptCallback {
                        if (args.size == 2 && args[1] is Boolean) {
                            extensions.getString(text(0), args[1] as Boolean)
                        } else
                            extensions.getString(
                                optional(0),
                                args.getOrNull(1),
                                args.getOrNull(2) == true,
                            )
                    }
                "ui.getStringList" ->
                    extensions.analyzeRule.withScriptCallback {
                        extensions.getStringList(
                            optional(0),
                            args.getOrNull(1),
                            args.getOrNull(2) == true,
                        )
                    }
                "ui.setContent" -> {
                    extensions.setContent(args.getOrNull(0), optional(1))
                    null
                }
                "ui.setBaseUrl" -> {
                    extensions.setBaseUrl(optional(0))
                    null
                }
                "ui.setRedirectUrl" -> extensions.setRedirectUrl(text(0))?.toString()
                else ->
                    withContext(Dispatchers.Main.immediate) {
                        context.ensureActive()
                        when (method) {
                            "ui.searchBook" -> extensions.searchBook(text(0), optional(1))
                            "ui.addBook" -> extensions.addBook(text(0))
                            "ui.showPhoto" -> extensions.showPhoto(text(0))
                            "ui.open" ->
                                extensions.open(text(0), optional(1), optional(2), optional(3))
                            "browser.show" ->
                                extensions.showBrowser(
                                    text(0),
                                    optional(1),
                                    optional(2),
                                    optional(3),
                                )
                            "browser.start" ->
                                extensions.startBrowser(text(0), text(1), optional(2))
                            "browser.openUrl" -> extensions.openUrl(text(0), optional(1))
                            "browser.video" ->
                                extensions.openVideoPlayer(
                                    text(0),
                                    text(1),
                                    args.getOrNull(2) == true,
                                )
                            else -> error("Unsupported UI script callback: $method")
                        }
                        null
                    }
            }
        }
        return withContext(callbacks) {
            V8ScriptExecutor.evaluate(
                """
                (async () => {
                    const previousJava = globalThis.java;
                    const java = new Proxy(Object.create(null), {
                        get: (_, name) => ['get','put','searchBook','addBook','showPhoto','open',
                            'getString','getStringList','setContent','setBaseUrl','setRedirectUrl'].includes(String(name))
                            ? (...args) => {
                                const result = __sourceHostSync('ui.' + String(name), args);
                                return ['setContent','setBaseUrl'].includes(String(name)) ? java : result;
                            }
                            : previousJava && previousJava[name]
                    });
                    return await eval(uiScript);
                })()
                """
                    .trimIndent(),
                BookSourceScriptBridge.jsonBindings(bindings) + ("uiScript" to script),
                source = source,
            )
        }
    }
}

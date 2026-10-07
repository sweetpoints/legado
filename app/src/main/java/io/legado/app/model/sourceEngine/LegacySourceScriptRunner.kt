package io.legado.app.model.sourceEngine

import android.os.Looper
import io.legado.app.data.entities.BaseSource
import kotlin.coroutines.CoroutineContext

/** Synchronous legacy entity helpers are lexical adapters; modern source APIs stay async. */
object LegacySourceScriptRunner {
    fun evaluateBlocking(
        script: String,
        bindings: Map<String, Any?>,
        context: CoroutineContext,
        source: BaseSource,
    ): Any? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "V8 scripts must be awaited on the main thread"
        }
        return V8ScriptExecutor.evaluateBlocking(
            wrap(),
            bindings + ("__baseSourceScript" to script),
            context,
            source = source,
        )
    }

    internal fun prelude(library: String): String =
        """
        globalThis.__legacyNativeJava = globalThis.__legacyNativeJava || globalThis.java;
        globalThis.java = new Proxy(globalThis.__legacyNativeJava, {
            get: (target, name) => ['get','put'].includes(String(name))
                ? (...args) => name === 'get' && args.length !== 1
                    ? target[name](...args)
                    : __sourceHostSync('analyze.' + String(name), args)
                : ['getString','getStringList','getElement','getElements'].includes(String(name))
                    && globalThis.__legacyExtractionPrefix === 'analyze'
                    ? (...args) => {
                        const value = __sourceHostSync('analyze.' + String(name), args);
                        return ['getElement','getElements'].includes(String(name)) ? __legacyDomMaterialize(value) : value;
                    }
                : ['getString','getStringList'].includes(String(name))
                    && globalThis.__legacyExtractionPrefix === 'ui'
                    ? (...args) => __sourceHostSync('ui.' + String(name), args)
                : ['getLoginInfo','putLoginInfo','getLoginHeader','putLoginHeader','getVariable','putVariable','removeLoginInfo'].includes(String(name))
                    ? (...args) => __sourceHostSync('sourceState.' + String(name), args)
                    : target[name]
        });
        globalThis.__legacyAsyncSource = globalThis.__legacyAsyncSource || globalThis.source;
        globalThis.source = new Proxy(globalThis.__legacyAsyncSource, {
            get: (target, name) => name === 'getTag' ? () => __legacySourceTag
                : name === 'getKey' ? () => __legacySourceKey
                : ['get','put'].includes(String(name))
                    ? (...args) => __sourceHostSync('analyze.' + String(name), args)
                    : ['getLoginInfo','putLoginInfo','getLoginHeader','putLoginHeader','getVariable','putVariable','removeLoginInfo'].includes(String(name))
                        ? (...args) => __sourceHostSync('sourceState.' + String(name), args)
                        : Object.prototype.hasOwnProperty.call(sourceData, name)
                            ? sourceData[name] : target[name]
        });
        globalThis.sourceApi = globalThis.source;
        $library
    """
            .trimIndent()

    internal fun wrap(): String =
        """
        (async function() {
            var nativeJava = globalThis.java;
            var java = new Proxy(Object.create(null), {
                get: (_, name) => ['get','put'].includes(String(name))
                    ? (...args) => name === 'get' && args.length !== 1
                        ? nativeJava[name](...args)
                        : __sourceHostSync('analyze.' + String(name), args)
                    : ['getLoginInfo','putLoginInfo','getLoginHeader','putLoginHeader','getVariable','putVariable','removeLoginInfo'].includes(String(name))
                        ? (...args) => __sourceHostSync('sourceState.' + String(name), args)
                        : nativeJava && nativeJava[name]
            });
            return await eval(__baseSourceScript);
        }).call(globalThis)
        """
            .trimIndent()
}

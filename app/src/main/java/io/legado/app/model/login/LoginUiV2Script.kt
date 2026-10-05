package io.legado.app.model.login

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.sourceEngine.BookSourceScriptBridge
import io.legado.app.utils.GSON

/** Login functions run inside one async scope and cross the host only as JSON. */
object LoginUiV2Script {
    fun render(loginJs: String): String =
        """
        (async()=>{
        $loginJs
        return await loginUi(JSON.parse(String(__loginState)));
        })()
    """
            .trimIndent()

    fun action(loginJs: String): String =
        """
        (async()=>{
        $loginJs
        return await loginAction(String(__loginAction),JSON.parse(String(__loginState)),JSON.parse(String(__loginForm)));
        })()
    """
            .trimIndent()

    fun bindings(
        stateJson: String,
        book: Book? = null,
        chapter: BookChapter? = null,
        action: String? = null,
        formJson: String? = null,
    ): Map<String, Any?> =
        BookSourceScriptBridge.jsonBindings(
            buildMap {
                put("__loginState", stateJson)
                put("book", book)
                put("chapter", chapter)
                if (action != null) put("__loginAction", action)
                if (formJson != null) put("__loginForm", formJson)
            }
        )

    fun jsonResult(result: Any?): String? =
        when (result) {
            null -> null
            is String -> result
            else -> GSON.toJson(result)
        }
}

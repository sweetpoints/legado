package io.legado.app.model.sourceEngine

import io.legado.app.utils.GSON

/** Restricts auxiliary source mutations to the documented string variable subset. */
data class SourceVariablePatch(
    val book: Map<String, String?>,
    val chapter: Map<String, String?>,
) {
    companion object {
        fun fromResult(value: Any?): SourceVariablePatch {
            val result = value as? Map<*, *> ?: error("Source callback must return a patch object")
            require(result.keys == setOf("bookVariables", "chapterVariables")) {
                "Unsupported source callback patch fields"
            }
            fun variables(value: Any?): Map<String, String?> {
                val variables =
                    value as? Map<*, *> ?: error("Source variable patch must be an object")
                return variables.entries.associate { (key, item) ->
                    require(key is String && (item == null || item is String)) {
                        "Source variable patch requires string keys and string or null values"
                    }
                    key to (item as String?)
                }
            }
            return SourceVariablePatch(
                variables(result["bookVariables"]),
                variables(result["chapterVariables"]),
            )
        }

        fun script(code: String): String =
            """
            (async () => {
                function facade(object, initial) {
                    const values = Object.assign(Object.create(null), initial);
                    const delta = Object.create(null);
                    Object.defineProperties(object, {
                        getVariable: {value: key => values[String(key)] ?? ''},
                        putVariable: {value: (key, value) => {
                            if (typeof key !== 'string' || (value !== null && typeof value !== 'string'))
                                throw new Error('Variables require string keys and string or null values');
                            const existed = Object.prototype.hasOwnProperty.call(values, key);
                            if (value === null) delete values[key]; else values[key] = value;
                            delta[key] = value;
                            return value === null ? existed : true;
                        }}
                    });
                    return delta;
                }
                const baseUrl = imageBaseUrl;
                const bookVariables = facade(book, initialBookVariables);
                const chapterVariables = facade(chapter, initialChapterVariables);
                await eval(${GSON.toJson(code)});
                return {bookVariables, chapterVariables};
            })()
        """
                .trimIndent()
    }
}

package io.legado.app.model.sourceEngine

import io.legado.app.exception.BookSourceLegacyEngineRemovedException

/** Only book sources are removed from the shared legacy engine. */
object BookSourceLegacyEnginePolicy {
    fun requireLegacyAllowed(isBookSource: Boolean) {
        if (isBookSource) rejectBookSourceExecution()
    }

    fun rejectBookSourceExecution(): Nothing = throw BookSourceLegacyEngineRemovedException()
}

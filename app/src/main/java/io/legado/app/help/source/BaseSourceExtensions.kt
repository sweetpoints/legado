package io.legado.app.help.source

import io.legado.app.constant.SourceType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.SharedJsScope
import io.legado.app.model.sourceEngine.DartSourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Library text and the owning V8 context are invalidated together. */
fun BaseSource.clearSharedGlobalState() {
    val owner = getSource() ?: this
    SharedJsScope.remove(owner.jsLib)
    runBlocking(getSourceNavigationContext() + Dispatchers.IO) {
        DartSourceEngine.clearSourceState(owner)
    }
}

fun clearSharedGlobalStateBySourceKey(sourceClass: Class<out BaseSource>, sourceKey: String) {
    runBlocking(Dispatchers.IO) {
        DartSourceEngine.clearSourceState("${sourceClass.name}:$sourceKey")
    }
}

fun BaseSource.getSourceType(): Int =
    when (val source = getSource() ?: this) {
        is BookSource -> SourceType.book
        is RssSource -> SourceType.rss
        else -> error("unknown source type: ${source::class.simpleName}.")
    }

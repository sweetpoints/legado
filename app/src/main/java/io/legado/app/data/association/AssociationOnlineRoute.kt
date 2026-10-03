package io.legado.app.data.association

/** Preserve the original public legado/yuedu paths and the older importonline host aliases. */
data class AssociationOnlineRoute(val importType: String? = null, val readConfig: Boolean = false)

fun associationOnlineRoute(path: String?, host: String?): AssociationOnlineRoute =
    when (path) {
        "/bookSource" -> AssociationOnlineRoute("bookSource")
        "/rssSource" -> AssociationOnlineRoute("rssSource")
        "/replaceRule" -> AssociationOnlineRoute("replaceRule")
        "/textTocRule" -> AssociationOnlineRoute("txtRule")
        "/httpTTS" -> AssociationOnlineRoute("httpTts")
        "/dictRule" -> AssociationOnlineRoute("dictRule")
        "/theme" -> AssociationOnlineRoute("theme")
        "/autoTask" -> AssociationOnlineRoute("autoTask")
        "/addToBookshelf" -> AssociationOnlineRoute("addToBookshelf")
        "/readConfig" -> AssociationOnlineRoute(readConfig = true)
        "/importonline" ->
            when (host) {
                "booksource" -> AssociationOnlineRoute("bookSource")
                "rsssource" -> AssociationOnlineRoute("rssSource")
                "replace" -> AssociationOnlineRoute("replaceRule")
                else -> AssociationOnlineRoute()
            }
        else -> AssociationOnlineRoute()
    }

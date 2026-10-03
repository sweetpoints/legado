package io.legado.app.model.settings

import io.legado.app.constant.PreferKey

internal enum class OtherSwitch(val key: String, val default: Boolean) {
    AutoRefresh(PreferKey.autoRefresh, false), OnlyRead(PreferKey.onlyUpdateRead, false), DefaultRead("defaultToRead", false),
    Discovery(PreferKey.showDiscovery, true), DiscoveryScroller(PreferKey.showDiscoveryFastScroller, false), Rss(PreferKey.showRss, true),
    WebWake(PreferKey.webServiceWakeLock, false), Cronet(PreferKey.cronet, false), AntiAlias(PreferKey.antiAlias, false),
    ReplaceDefault(PreferKey.replaceEnableDefault, true), MediaExit("mediaButtonOnExit", true), MediaRead(PreferKey.readAloudByMediaButton, false),
    IgnoreFocus(PreferKey.ignoreAudioFocus, false), AutoClear(PreferKey.autoClearExpired, true), AddAlert(PreferKey.showAddToShelfAlert, true),
    AutoUpdate("autoUpdateVariant", true), LiveNotifications(PreferKey.liveUpdateNotifications, false), Manga(PreferKey.showMangaUi, true),
    TokenRequired(PreferKey.jsSourceApiTokenRequired, true), ProcessText(PreferKey.processText, true), Log(PreferKey.recordLog, false),
    HttpLog(PreferKey.recordHttpLog, false), HeapDump(PreferKey.recordHeapDump, false)
}
internal enum class OtherNumber(val key: String, val default: Int, val minimum: Int, val maximum: Int) {
    PreDownload(PreferKey.preDownloadNum, 2, 0, 9999), Threads(PreferKey.threadCount, 32, 1, 999),
    WebPort(PreferKey.webPort, 1122, 1024, 60000), McpPort(PreferKey.mcpPort, 1236, 1024, 65530),
    BitmapCache(PreferKey.bitmapCacheSize, 50, 1, 1024), ImageRetain(PreferKey.imageRetainNum, 0, 0, 999),
    SourceLines(PreferKey.sourceEditMaxLine, Int.MAX_VALUE, 10, Int.MAX_VALUE)
}
internal enum class OtherText(val key: String) { UserAgent(PreferKey.userAgent), Hosts(PreferKey.customHosts), Token(PreferKey.jsSourceApiToken), BookTree(PreferKey.defaultBookTreeUri) }
internal enum class OtherChoice(val key: String, val default: String) { Language(PreferKey.language, "auto"), Home(PreferKey.defaultHomePage, "bookshelf") }
internal enum class OtherEffect { ThreadsChanged, RestartWeb, RestartMcp, StopMcp, LogConfiguration, DownloadCronet, NotifyMain, RestartApplication, ResizeBitmapCache, PromotedNotificationSettings, ProcessTextConfiguration }
internal data class OtherSettingsSnapshot(val switches: Map<OtherSwitch, Boolean> = OtherSwitch.entries.associateWith { it.default },
    val numbers: Map<OtherNumber, Int> = OtherNumber.entries.associateWith { it.default },
    val texts: Map<OtherText, String> = OtherText.entries.filter { it != OtherText.Token }.associateWith { "" },
    val choices: Map<OtherChoice, String> = OtherChoice.entries.associateWith { it.default },
    val tokenConfigured: Boolean = false, val promotedNotificationsVisible: Boolean = false, val checkSourceSummary: String = "") {
    fun visible(key: OtherSwitch) = when (key) { OtherSwitch.OnlyRead -> switches.getValue(OtherSwitch.AutoRefresh)
        OtherSwitch.LiveNotifications -> promotedNotificationsVisible; else -> true }
}

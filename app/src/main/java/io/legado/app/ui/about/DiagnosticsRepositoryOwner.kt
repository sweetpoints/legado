package io.legado.app.ui.about
import io.legado.app.data.repository.AppLogsRepository
import io.legado.app.data.repository.CrashLogsRepository
/** Optional native host repository ownership; no intent or Flutter argument can select a repository. */
interface DiagnosticsRepositoryOwner {
    fun appLogsRepository(): AppLogsRepository
    fun crashLogsRepository(): CrashLogsRepository
}

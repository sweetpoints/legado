package io.legado.app.ui.about

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** Debug-only synthetic source. No AppLog store, HTTP store, crash directory or backup URI is read. */
class DiagnosticsFixtureActivity : BaseThemedActivity(), DiagnosticsRepositoryOwner {
    val fixture by viewModels<DiagnosticsFixtureModel>()
    override fun createContentView() { setContentView(FrameLayout(this)) }
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if(savedInstanceState == null) {
            if(intent.getBooleanExtra("crash", false)) CrashLogsDialog().showNow(supportFragmentManager, "logs")
            else AppLogDialog().showNow(supportFragmentManager, "logs")
        }
    }
    override fun appLogsRepository(): AppLogsRepository = fixture.app
    override fun crashLogsRepository(): CrashLogsRepository = fixture.crash
    override fun startActivity(intent: Intent) {
        if(intent.action == Intent.ACTION_CHOOSER) {
            if(fixture.rejectShare) throw IllegalStateException("Synthetic share failure")
            fixture.choosers += Intent(intent)
            if(fixture.launchRealChooser) super.startActivity(intent)
        } else super.startActivity(intent)
    }
}
class DiagnosticsFixtureModel : ViewModel() {
    val entries = MutableStateFlow(listOf(AppLogRow(1, "12:00", "Synthetic first", true)))
    var subscribers = 0
    var clears = 0
    var exportMode = "text"
    var rejectShare = false
    var launchRealChooser = false
    var exportDirectory: File? = null
    val choosers = mutableListOf<Intent>()
    val app = object : AppLogsRepository {
        override val logs = entries.onStart { subscribers++ }.onCompletion { subscribers-- }
        override suspend fun readDetail(id: Long) = entries.value.singleOrNull { it.id == id }?.let {
            AppLogDetail(id, "Synthetic details", "synthetic-private-detail-".repeat(4000))
        }
        override suspend fun clearLogs() { clears++; entries.value = emptyList() }
        override suspend fun prepareExport(): AppLogExport? = when(exportMode) {
            "empty" -> null
            "file" -> AppLogExport.Document(File.createTempFile("synthetic-diagnostics-", ".txt", requireNotNull(exportDirectory)).apply { writeText("synthetic-export-".repeat(5000)) })
            else -> AppLogExport.Text("synthetic-short-export")
        }
    }
    var crashEntries = (0..104).map { CrashLogEntry("fixture-native-$it", "Synthetic-$it.log") }
    var crashFailure = false
    var partialClear = false
    var crashReads = 0
    val crash = object : CrashLogsRepository {
        override suspend fun loadLogs(): List<CrashLogEntry> {
            check(!crashFailure) { "Synthetic load failure" }; return crashEntries
        }
        override suspend fun readLog(id: String): CrashLogContent {
            val row = requireNotNull(crashEntries.singleOrNull { it.id == id }); crashReads++
            return CrashLogContent(row, "synthetic-crash-private-".repeat(4000))
        }
        override suspend fun clearLogs() {
            if(partialClear) { crashEntries = crashEntries.takeLast(1); error("Synthetic partial clear") }
            crashEntries = emptyList()
        }
    }
}

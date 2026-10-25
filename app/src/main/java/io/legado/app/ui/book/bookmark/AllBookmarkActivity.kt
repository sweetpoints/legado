package io.legado.app.ui.book.bookmark

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseThemedActivity
import io.legado.app.data.repository.AllBookmarksDestination
import io.legado.app.data.repository.AppAllBookmarksRepository
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi

/** Normal My -> bookmarks entry. Room, file contracts and reading remain native. */
class AllBookmarkActivity : BaseThemedActivity() {
    internal val model by viewModels<AllBookmarksViewModel> {
        viewModelFactory { initializer { AllBookmarksViewModel(AppAllBookmarksRepository(), createSavedStateHandle()) } }
    }
    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        model.directoryResult(result.uri?.toString(), result.value ?: model.waitingDirectory())
    }
    internal val coordinator by lazy {
        AllBookmarksNativeCoordinator(model, lifecycle, lifecycleScope, ::ready, ::finish,
            ::open, { markdown -> exportDir.launch {
                requestCode = if (markdown) 2 else 1
                value = model.waitingDirectory()
            } }, { toastOnUi(it) }, getString(R.string.export_success), getString(R.string.unknown_error))
    }
    private val fragment get() = supportFragmentManager.findFragmentByTag(TAG) as? AllBookmarksFlutterFragment
    override fun createContentView() { setContentView(FrameLayout(this).apply { id = R.id.all_bookmarks_flutter_content }) }
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        coordinator
        onBackPressedDispatcher.addCallback(this) { fragment?.onBackPressed() ?: finish() }
        if (fragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.all_bookmarks_flutter_content, AllBookmarksFlutterFragment(), TAG).commitNow()
    }
    private fun ready() = !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved
    private fun open(destination: AllBookmarksDestination, position: Int) {
        val book = destination.book
        if (book == null) BookmarkDialog(destination.bookmark, position).show(supportFragmentManager, "all-bookmark-editor")
        else startActivityForBook(book) {
            putExtra("index", destination.bookmark.chapterIndex)
            putExtra("chapterPos", destination.bookmark.chapterPos)
        }
    }
    override fun onPostResume() { super.onPostResume(); fragment?.onPostResume() }
    override fun onNewIntent(intent: Intent) { fragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); fragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); fragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); fragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); fragment?.onTrimMemory(level) }
    override fun onDestroy() { coordinator.close(); super.onDestroy() }
    private companion object { const val TAG = "all-bookmarks-flutter" }
}

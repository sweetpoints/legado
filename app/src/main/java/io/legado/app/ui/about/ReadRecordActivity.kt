package io.legado.app.ui.about

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.*
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.toastOnUi

class ReadRecordActivity : BaseComposeActivity() {
    val viewModel by viewModels<ReadingHistoryViewModel> { viewModelFactory { initializer {
        ReadingHistoryViewModel(RoomReadingHistoryRepository(applicationContext), FileReadingHistoryDraftRepository(applicationContext), createSavedStateHandle())
    } } }
    private val covers by lazy { GlideReadingHistoryCoverRepository(applicationContext) }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        ReadingHistoryRoute(viewModel, covers, { super.finish() }, ::open, { toastOnUi(it) }, { !supportFragmentManager.isStateSaved })
    }
    private fun open(destination: ReadingHistoryDestination) {
        if (destination.kind == ReadingHistoryReader.Search) { SearchActivity.start(this, destination.name); return }
        val cls = when (destination.kind) {
            ReadingHistoryReader.Audio -> AudioPlayActivity::class.java
            ReadingHistoryReader.Video -> VideoPlayerActivity::class.java
            ReadingHistoryReader.Manga -> ReadMangaActivity::class.java
            else -> ReadBookActivity::class.java
        }
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("bookUrl", destination.key))
    }
}

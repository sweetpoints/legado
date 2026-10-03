package io.legado.app.ui.book.changesource

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.preferences.AppChapterSourceSettingsRepository
import io.legado.app.data.repository.*
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Book source selection, with immutable Compose state and application-context data stores. */
class ChangeBookSourceDialog() : BaseComposeDialogFragment(), ChangeSourceWordCountFilterCallback {
    constructor(name: String, author: String) : this() {
        arguments =
            Bundle().apply {
                putString("name", name)
                putString("author", author)
            }
    }

    private val callBack: CallBack?
        get() = activity as? CallBack

    private val groupsRepository = RoomChapterSourceGroupRepository()
    private val model by
        viewModels<BookSourceViewModel> {
            viewModelFactory {
                initializer {
                    val context = requireContext().applicationContext
                    val name = arguments?.getString("name").orEmpty()
                    val author = arguments?.getString("author").orEmpty()
                    val book = callBack?.oldBook?.copy()
                    val fromReader = activity is ReadBookActivity
                    val search = AppChapterSourceSearchStore()
                    BookSourceViewModel(
                        DefaultBookSourceSearchRepository(AppBookSourceSearchStore(search)),
                        DefaultBookSourceChangeRepository(
                            AppBookSourceChangeStore(context, search = search)
                        ),
                        AppChapterSourceSettingsRepository(),
                        createSavedStateHandle(),
                    ) {
                        withContext(Dispatchers.Default) {
                            BookSourceChangeSession(
                                ChapterSourceSearchRequest(
                                    name,
                                    author.replace(AppPattern.authorRegex, ""),
                                    originalBookJson = book?.let(GSON::toJson),
                                    currentBookUrl = book?.bookUrl,
                                    fromReader = fromReader,
                                )
                            )
                        }
                    }
                }
            }
        }
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            it.data?.getStringExtra("origin")?.let(model::startSearch)
        }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCancel(dialog: DialogInterface) {
        model.stop()
        super.onCancel(dialog)
    }

    override fun onWordCountFilterChanged(reloadMeasurements: Boolean) {
        model.optionsChanged(reloadMeasurements)
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            callBack?.oldBook?.let(model::updateCurrent)
        }
    }

    @Composable
    override fun Content() {
        BookSourceRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved && callBack != null },
            ::deliver,
            { action ->
                when (action) {
                    BookSourceMenu.Manage -> startActivity<BookSourceActivity>()
                    BookSourceMenu.WordCountFilter -> showChangeSourceWordCountFilter()
                    else -> Unit
                }
            },
            { origin -> editSourceResult.launch { putExtra("sourceUrl", origin) } },
            ::close,
            Modifier.fillMaxSize(),
            groupsRepository,
            { toastOnUi(R.string.change_source_relative_word_count_unavailable) },
        )
    }

    private fun close() {
        model.stop()
        dismissAllowingStateLoss()
    }

    private fun deliver(value: BookSourceDelivery) {
        val callback = callBack ?: return
        val capturedModel = model
        callback.changeTo(value.source, value.book, value.chapters) {
            // Existing host completion may arrive after the fragment closes. It owns this bounded
            // task.
            Coroutine.async(context = Dispatchers.Main.immediate) {
                    capturedModel.completeReceipt(value.receipt)
                }
                .onError { AppLog.put("换源成功后更新书源失败\n${it.localizedMessage}", it, true) }
        }
    }

    interface CallBack {
        val oldBook: Book?

        fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>, onSuccess: () -> Unit)
    }
}

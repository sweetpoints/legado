package io.legado.app.ui.book.info.detail

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookDetailNativeKind
import io.legado.app.data.repository.BookDetailNativePayload
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.autoTask.AutoTaskEditActivity
import io.legado.app.ui.autoTask.ImportAutoTaskDialog
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class BookDetailNativeLaunchers(
    val toc: (BookDetailNativePayload) -> Unit,
    val reader: (BookDetailNativePayload) -> Unit,
    val infoEditor: (BookDetailNativePayload) -> Unit,
    val sourceEditor: (BookDetailNativePayload) -> Unit,
    val folder: (BookDetailNativePayload) -> Unit,
)

/**
 * All payload parsing/Room work has finished on IO before the Route's resumed claim calls this
 * host.
 */
class BookDetailNativeHost(
    private val activity: AppCompatActivity,
    private val launchers: BookDetailNativeLaunchers,
    private val compatibilityBook: (Book?) -> Unit,
    private val closeDeleted: () -> Unit,
    private val clearCache: (BookDetailNativePayload) -> Unit,
) {
    fun deliver(payload: BookDetailNativePayload) {
        val effect = payload.effect
        val book = payload.book
        if (book != null) compatibilityBook(book)
        when (effect.kind) {
            BookDetailNativeKind.Log -> activity.showDialogFragment<AppLogDialog>()
            BookDetailNativeKind.Toast ->
                activity.toastOnUi(
                    if (payload.text == "unsupported_archive")
                        activity.getString(R.string.unsupport_archivefile_entry)
                    else payload.text
                )
            BookDetailNativeKind.ReaderSync -> applyBookDetailReaderPayload(payload)
            BookDetailNativeKind.SourceChanged ->
                book?.let { postEvent(EventBus.SOURCE_CHANGED, it.bookUrl) }
            BookDetailNativeKind.ShelfAdded ->
                SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, payload.source, book)
            BookDetailNativeKind.Deleted -> {
                if (effect.flag)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, payload.source, book)
                closeDeleted()
            }
            BookDetailNativeKind.ClearCacheRequest ->
                callback(payload, SourceCallBack.CLICK_CLEAR_CACHE) { clearCache(payload) }
            BookDetailNativeKind.CacheCleared -> {
                if (book != null && ReadBook.book?.bookUrl == book.bookUrl)
                    ReadBook.clearTextChapter()
                if (book != null && ReadManga.book?.bookUrl == book.bookUrl)
                    ReadManga.clearMangaChapter()
                activity.toastOnUi(R.string.clear_cache_success)
            }
            BookDetailNativeKind.OpenFile ->
                payload.text?.let { activity.openFileUri(it.toUri(), "*/*") }
            BookDetailNativeKind.IntroLink -> payload.text?.let { activity.openUrl(it) }
            BookDetailNativeKind.EditInfo -> launchers.infoEditor(payload)
            BookDetailNativeKind.EditSource -> launchers.sourceEditor(payload)
            BookDetailNativeKind.Toc -> launchers.toc(payload)
            BookDetailNativeKind.Reader -> launchers.reader(payload)
            BookDetailNativeKind.ChooseFolder -> launchers.folder(payload)
            BookDetailNativeKind.Photo,
            BookDetailNativeKind.IntroImage ->
                payload.text?.let {
                    activity.showDialogFragment(
                        PhotoDialog(
                            it,
                            if (effect.kind == BookDetailNativeKind.Photo)
                                effect.book?.cover?.sourceOrigin
                            else payload.source?.bookSourceUrl,
                            effect.flag,
                        )
                    )
                }
            BookDetailNativeKind.ChangeCover ->
                book?.let { activity.showDialogFragment(ChangeCoverDialog(it.name, it.author)) }
            BookDetailNativeKind.ChangeSource ->
                book?.let {
                    activity.showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            BookDetailNativeKind.Group ->
                book?.let { activity.showDialogFragment(GroupSelectDialog(it.group)) }
            BookDetailNativeKind.Login ->
                payload.source?.let { source ->
                    activity.startActivity(
                        Intent(activity, SourceLoginActivity::class.java)
                            .putExtra("type", "bookSource")
                            .putExtra("key", source.bookSourceUrl)
                            .putExtra("bookUrl", book?.bookUrl)
                    )
                }
            BookDetailNativeKind.Share ->
                book?.let { value ->
                    callback(payload, SourceCallBack.CLICK_SHARE_BOOK, payload.text) {
                        activity.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra(Intent.EXTRA_TEXT, payload.text)
                                    .setType("text/plain"),
                                value.name,
                            )
                        )
                    }
                }
            BookDetailNativeKind.CopyBookUrl ->
                callback(payload, SourceCallBack.CLICK_COPY_BOOK_URL, payload.text) {
                    activity.sendToClip(payload.text.orEmpty())
                }
            BookDetailNativeKind.CopyTocUrl ->
                callback(payload, SourceCallBack.CLICK_COPY_TOC_URL, payload.text) {
                    activity.sendToClip(payload.text.orEmpty())
                }
            BookDetailNativeKind.SearchName ->
                book?.let { value ->
                    callback(
                        payload,
                        if (effect.flag) SourceCallBack.LONG_CLICK_BOOK_NAME
                        else SourceCallBack.CLICK_BOOK_NAME,
                        value.name,
                    ) {
                        SearchActivity.start(activity, value.name)
                    }
                }
            BookDetailNativeKind.SearchAuthor ->
                book?.let { value ->
                    callback(
                        payload,
                        if (effect.flag) SourceCallBack.LONG_CLICK_AUTHOR
                        else SourceCallBack.CLICK_AUTHOR,
                        value.author,
                    ) {
                        SearchActivity.start(activity, value.author)
                    }
                }
            BookDetailNativeKind.SearchKind ->
                payload.source?.let { source ->
                    callback(
                        payload,
                        if (effect.flag) SourceCallBack.LONG_CLICK_BOOK_LABEL
                        else SourceCallBack.CLICK_BOOK_LABEL,
                        payload.text,
                        if (effect.flag) null
                        else ({ SearchActivity.start(activity, source, payload.text) }),
                    )
                }
            BookDetailNativeKind.CustomButton ->
                callback(payload, SourceCallBack.CLICK_CUSTOM_BUTTON)
            BookDetailNativeKind.SourceVariable,
            BookDetailNativeKind.BookVariable ->
                payload.variable?.let { variable ->
                    activity.showDialogFragment(
                        VariableDialog(
                            activity.getString(
                                if (effect.kind == BookDetailNativeKind.SourceVariable)
                                    R.string.set_source_variable
                                else R.string.set_book_variable
                            ),
                            variable.key,
                            variable.value,
                            variable.comment,
                        )
                    )
                }
            BookDetailNativeKind.UpdateTask ->
                payload.task?.let { task ->
                    if (task.existingId != null)
                        activity.startActivity(
                            AutoTaskEditActivity.intent(activity, task.existingId)
                        )
                    else task.json?.let { activity.showDialogFragment(ImportAutoTaskDialog(it)) }
                }
            BookDetailNativeKind.IntroAction ->
                error("Introduction actions require the parsed typed action callback")
        }
    }

    fun callback(
        payload: BookDetailNativePayload,
        event: String,
        value: String? = null,
        fallback: (() -> Unit)? = null,
    ) {
        val book = payload.book ?: return
        SourceCallBack.callBackBtn(
            activity,
            event,
            payload.source,
            book,
            null,
            result = value,
            noCall = fallback,
        )
    }
}

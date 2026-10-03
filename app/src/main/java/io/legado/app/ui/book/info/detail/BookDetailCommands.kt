package io.legado.app.ui.book.info.detail

import io.legado.app.data.repository.BookDetailMutation
import io.legado.app.data.repository.BookDetailMutationKind
import io.legado.app.data.repository.BookDetailNativeKind
import io.legado.app.data.repository.BookDetailPreference
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.BookDetailPrompt
import io.legado.app.data.repository.BookDetailPromptKind
import io.legado.app.data.repository.BookDetailServiceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Maps UI intent to typed immutable operations. Native application/reader callbacks remain in the
 * host.
 */
data class BookDetailCommandTexts(
    val remoteNotConfigured: String,
    val chapterListEmpty: String,
    val missingWebFiles: String,
    val needMoreTime: String,
)

class BookDetailCommands(
    private val viewModel: BookDetailViewModel,
    private val preferencesViewModel: BookDetailPreferencesViewModel,
    private val commandScope: CoroutineScope,
    private val showMessage: (String) -> Unit,
    private val onNavigate: (BookDetailMutation, BookDetailNativeKind) -> Unit,
    private val onRequestClearCache: () -> Unit,
    private val messages: BookDetailCommandTexts,
) {
    private fun withCommittedPreferences(
        expectedPrompt: BookDetailPrompt? = null,
        block: (BookDetailPreferences) -> Unit,
    ) {
        commandScope.launch {
            try {
                val committedPreferences = preferencesViewModel.requireCommitted()
                ensureActive()
                val currentState = viewModel.state.value
                if (
                    currentState.canInteract &&
                        (expectedPrompt == null || currentState.session?.prompt == expectedPrompt)
                )
                    block(committedPreferences)
            } catch (error: Throwable) {
                ensureActive()
                if (!viewModel.state.value.closed) showMessage(error.message ?: error.toString())
            }
        }
    }

    fun action(action: BookDetailAction) {
        val state = viewModel.state.value
        val data = state.data ?: return
        val book = data.book
        when (action) {
            BookDetailAction.CustomButton -> viewModel.queue(BookDetailNativeKind.CustomButton)
            BookDetailAction.Edit -> viewModel.queue(BookDetailNativeKind.EditInfo)
            BookDetailAction.Share -> viewModel.queue(BookDetailNativeKind.Share)
            BookDetailAction.Upload ->
                withCommittedPreferences {
                    if (it.remoteConfigured) viewModel.service(BookDetailServiceKind.UploadCheck)
                    else showMessage(messages.remoteNotConfigured)
                }
            BookDetailAction.Refresh -> viewModel.service(BookDetailServiceKind.Refresh)
            BookDetailAction.RefreshToc -> viewModel.refreshToc()
            BookDetailAction.UpdateTask -> viewModel.queue(BookDetailNativeKind.UpdateTask)
            BookDetailAction.Login -> viewModel.queue(BookDetailNativeKind.Login)
            BookDetailAction.Top -> viewModel.mutate(BookDetailMutation(BookDetailMutationKind.Top))
            BookDetailAction.SourceVariable -> viewModel.queue(BookDetailNativeKind.SourceVariable)
            BookDetailAction.BookVariable -> viewModel.queue(BookDetailNativeKind.BookVariable)
            BookDetailAction.CopyBookUrl -> viewModel.queue(BookDetailNativeKind.CopyBookUrl)
            BookDetailAction.CopyTocUrl -> viewModel.queue(BookDetailNativeKind.CopyTocUrl)
            BookDetailAction.CanUpdate ->
                viewModel.mutate(
                    BookDetailMutation(BookDetailMutationKind.CanUpdate, flag = !book.canUpdate)
                )
            BookDetailAction.SplitLong -> {
                viewModel.mutate(
                    BookDetailMutation(
                        BookDetailMutationKind.SplitLong,
                        flag = !book.splitLongChapter,
                    )
                )
                if (book.splitLongChapter) showMessage(messages.needMoreTime)
                commandScope.launch {
                    val settledState = viewModel.state.first { !it.busy }
                    ensureActive()
                    if (
                        !settledState.closed &&
                            settledState.error == null &&
                            settledState.data?.book?.splitLongChapter == !book.splitLongChapter
                    )
                        viewModel.refreshInfo()
                }
            }
            BookDetailAction.DeleteAlert ->
                preferencesViewModel.toggle(BookDetailPreference.DeleteAlert)
            // The original source callback can intercept the cache operation; it receives an
            // IO-prepared native payload.
            BookDetailAction.ClearCache -> onRequestClearCache()
            BookDetailAction.Log -> viewModel.queue(BookDetailNativeKind.Log)
            BookDetailAction.Group -> viewModel.queue(BookDetailNativeKind.Group)
            BookDetailAction.ChangeSource -> viewModel.queue(BookDetailNativeKind.ChangeSource)
            BookDetailAction.Toc -> {
                if (data.chapters.isEmpty()) {
                    showMessage(messages.chapterListEmpty)
                    return
                }
                onNavigate(
                    BookDetailMutation(BookDetailMutationKind.PrepareToc),
                    BookDetailNativeKind.Toc,
                )
            }
            BookDetailAction.Shelf ->
                if (data.inBookshelf)
                    withCommittedPreferences { committedPreferences ->
                        if (committedPreferences.deleteAlert)
                            viewModel.prompt(
                                BookDetailPrompt(
                                    BookDetailPromptKind.Delete,
                                    deleteOriginal = committedPreferences.deleteOriginal,
                                )
                            )
                        else
                            viewModel.service(
                                BookDetailServiceKind.Delete,
                                deleteOriginal = committedPreferences.deleteOriginal,
                            )
                    }
                else if (book.isWebFile) webFiles(false)
                else viewModel.mutate(BookDetailMutation(BookDetailMutationKind.JoinShelf))
            BookDetailAction.Read ->
                if (book.isWebFile) webFiles(true)
                else
                    onNavigate(
                        BookDetailMutation(BookDetailMutationKind.PrepareRead),
                        BookDetailNativeKind.Reader,
                    )
        }
    }

    private fun webFiles(readAfter: Boolean) = withCommittedPreferences { committedPreferences ->
        if (viewModel.state.value.session?.webFiles.isNullOrEmpty())
            showMessage(messages.missingWebFiles)
        else
            viewModel.prompt(
                BookDetailPrompt(
                    BookDetailPromptKind.WebFiles,
                    uploadImported = committedPreferences.uploadImported,
                    readAfter = readAfter,
                )
            )
    }

    fun click(click: BookDetailClick, value: String?, long: Boolean) {
        when (click) {
            BookDetailClick.Name -> viewModel.queue(BookDetailNativeKind.SearchName, flag = long)
            BookDetailClick.Author ->
                viewModel.queue(BookDetailNativeKind.SearchAuthor, flag = long)
            BookDetailClick.Kind ->
                if (viewModel.state.value.data?.source != null)
                    viewModel.queue(BookDetailNativeKind.SearchKind, value, flag = long)
            BookDetailClick.Origin ->
                if (viewModel.state.value.data?.book?.isLocal == false)
                    viewModel.queue(BookDetailNativeKind.EditSource)
            BookDetailClick.Cover ->
                if (long) viewModel.queue(BookDetailNativeKind.ChangeCover)
                else
                    viewModel.queue(
                        BookDetailNativeKind.Photo,
                        viewModel.state.value.data?.book?.cover?.path,
                        flag = true,
                    )
        }
    }

    fun dismiss(prompt: BookDetailPrompt) {
        viewModel.prompt(null)
        if (prompt.kind == BookDetailPromptKind.OverwriteUpload && prompt.readAfter)
            onNavigate(
                BookDetailMutation(BookDetailMutationKind.PrepareRead),
                BookDetailNativeKind.Reader,
            )
    }

    fun confirm(prompt: BookDetailPrompt, index: Int? = null) {
        when (prompt.kind) {
            BookDetailPromptKind.Delete ->
                withCommittedPreferences(prompt) { committedPreferences ->
                    viewModel.prompt(null)
                    viewModel.service(
                        BookDetailServiceKind.Delete,
                        deleteOriginal = committedPreferences.deleteOriginal,
                        deleteRemote = prompt.deleteRemote,
                    )
                }
            BookDetailPromptKind.WebFiles -> {
                val file = viewModel.state.value.session?.webFiles?.getOrNull(index ?: -1) ?: return
                if (!file.supported && !file.archive)
                    viewModel.prompt(
                        prompt.copy(
                            kind = BookDetailPromptKind.UnsupportedFile,
                            value = file.url,
                            values = listOf(file.name),
                        )
                    )
                else
                    withCommittedPreferences(prompt) { committedPreferences ->
                        viewModel.prompt(null)
                        viewModel.service(
                            BookDetailServiceKind.Download,
                            file = file,
                            readAfter = prompt.readAfter,
                            uploadImported = committedPreferences.uploadImported,
                        )
                    }
            }
            BookDetailPromptKind.UnsupportedFile -> {
                val file =
                    viewModel.state.value.session?.webFiles?.firstOrNull { it.url == prompt.value }
                        ?: return
                viewModel.prompt(null)
                viewModel.service(BookDetailServiceKind.Download, file = file)
            }
            BookDetailPromptKind.ArchiveEntries -> {
                val entry = prompt.values.getOrNull(index ?: -1) ?: return
                viewModel.prompt(null)
                viewModel.service(
                    BookDetailServiceKind.ArchiveImport,
                    uri = prompt.value,
                    entry = entry,
                    readAfter = prompt.readAfter,
                    uploadImported = prompt.uploadImported,
                )
            }
            BookDetailPromptKind.Upload -> {
                viewModel.prompt(null)
                viewModel.service(BookDetailServiceKind.UploadCheck, readAfter = prompt.readAfter)
            }
            BookDetailPromptKind.OverwriteUpload -> {
                viewModel.prompt(null)
                viewModel.service(
                    BookDetailServiceKind.Upload,
                    overwrite = true,
                    readAfter = prompt.readAfter,
                )
            }
            BookDetailPromptKind.ExternalLink -> {
                viewModel.prompt(null)
                viewModel.queue(BookDetailNativeKind.IntroLink, prompt.value, flag = true)
            }
        }
    }

    fun deleteOriginal(value: Boolean) {
        preferencesViewModel.set(BookDetailPreference.DeleteOriginal, value)
    }

    fun uploadImported(value: Boolean) {
        preferencesViewModel.set(BookDetailPreference.UploadImported, value)
    }

    fun deleteRemote(prompt: BookDetailPrompt, value: Boolean) {
        viewModel.prompt(prompt.copy(deleteRemote = value))
    }
}

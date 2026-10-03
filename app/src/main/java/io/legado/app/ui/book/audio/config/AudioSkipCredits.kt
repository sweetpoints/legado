package io.legado.app.ui.book.audio.config

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.model.AudioPlay
import io.legado.app.ui.book.audio.AudioNavigationCheckpoint
import io.legado.app.ui.book.audio.AudioPlaybackCheckpoint
import io.legado.app.ui.book.audio.FileAudioPlaybackSessions
import io.legado.app.utils.setLayout
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

class AudioSkipCredits : BaseComposeDialogFragment() {
    private var liveBook: WeakReference<Book>? = null
    private var seed: Book? = null
    private val ticket: String
        get() = requireNotNull(arguments?.getString(SESSION_KEY))

    private val repository by lazy {
        AudioSkipCreditsSessionRepository(ticket, sessions(requireContext()), seed)
    }
    private val model by
        viewModels<AudioSkipCreditsViewModel> {
            viewModelFactory {
                initializer {
                    val saved = createSavedStateHandle()
                    saved.remove<String>("bookUrl")
                    AudioSkipCreditsViewModel(repository, saved)
                }
            }
        }

    /** The caller claims only while RESUMED, immediately before showing this native dialog. */
    internal suspend fun claimLaunch(context: Context): Boolean {
        val files = sessions(context)
        val payload = files.read(ticket) ?: return false
        val navigation = payload.navigation ?: return false
        if (navigation.claimed) return false
        return files.write(
            ticket,
            payload.copy(
                revision = payload.revision + 1,
                navigation = navigation.copy(claimed = true),
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        AudioSkipCreditsRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            { draft ->
                val id = repository.bookId
                val book =
                    liveBook?.get()?.takeIf { it.bookUrl == id }
                        ?: AudioPlay.book?.takeIf { it.bookUrl == id }
                book?.config?.apply {
                    useGlobalAudioSkip = draft.useGlobal
                    openCredits = draft.bookOpen
                    closeCredits = draft.bookClose
                }
            },
            ::dismissAllowingStateLoss,
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true && !model.state.value.finished) {
            model.requestClose()
        }
        super.onDismiss(dialog)
    }

    companion object {
        internal const val SESSION_KEY = "audioSkipCredits.session"

        // Compatibility entry: no URL or Book ever enters Fragment arguments. The repository
        // accepts its private snapshot on IO before exposing editable configuration.
        fun newInstance(book: Book) =
            instance(UUID.randomUUID().toString(), book, retainSeed = true)

        internal suspend fun prepare(context: Context, book: Book): AudioSkipCredits {
            val ticket = UUID.randomUUID().toString()
            val snapshot = book.copy(readConfig = book.readConfig?.copy())
            sessions(context)
                .write(
                    ticket,
                    AudioPlaybackCheckpoint(
                        navigation = AudioNavigationCheckpoint(ticket, snapshot)
                    ),
                )
            return instance(ticket, book, retainSeed = false)
        }

        private fun instance(ticket: String, book: Book, retainSeed: Boolean) =
            AudioSkipCredits().apply {
                arguments = Bundle().apply { putString(SESSION_KEY, ticket) }
                liveBook = WeakReference(book)
                if (retainSeed) seed = book.copy(readConfig = book.readConfig?.copy())
            }

        private fun sessions(context: Context) =
            FileAudioPlaybackSessions(File(context.filesDir, "audio-skip-credits-sessions"))
    }
}

package io.legado.app.ui.book.manage

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.RoomBookSourcePickerRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout

class SourcePickerDialog : BaseComposeDialogFragment() {
    private val model by
        viewModels<BookSourcePickerViewModel> {
            viewModelFactory {
                initializer {
                    BookSourcePickerViewModel(
                        RoomBookSourcePickerRepository(),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        isCancelable = false
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !model.state.value.busy) {
                    if (model.state.value.delayOpen) model.closeDelay() else model.cancel()
                }
                true
            }
        }
    }

    @Composable
    override fun Content() {
        BookSourcePickerRoute(
            model,
            { json ->
                val source = GSON.fromJsonObject<BookSource>(json).getOrThrow()
                ((parentFragment as? Callback) ?: (activity as? Callback))?.sourceOnClick(source)
            },
            ::dismissAllowingStateLoss,
        )
    }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }
}

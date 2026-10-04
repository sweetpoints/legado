package io.legado.app.ui.widget.dialog

import io.legado.app.utils.resizeForIme

import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.GlideMarkdownImageRepository
import io.legado.app.data.repository.RoomBookMemoRepository
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.config.PaddingPanelVisibility
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import kotlinx.coroutines.launch

class BookMemoDialog : BaseComposeDialogFragment() {
    private val model by
        viewModels<BookMemoViewModel> {
            viewModelFactory {
                initializer {
                    BookMemoViewModel(
                        RoomBookMemoRepository(requireContext()),
                        createSavedStateHandle(),
                        requireArguments().getString("bookUrl")!!,
                    )
                }
            }
        }
    private val images by lazy { GlideMarkdownImageRepository(requireContext()) }
    private val visibility = PaddingPanelVisibility()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, .5f)
        dialog?.window?.apply {
            setGravity(Gravity.BOTTOM)
            resizeForIme()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, .5f)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        (activity as? ReadBookActivity)?.let { host ->
            visibility.acquire(
                object : PaddingPanelVisibility.Owner {
                    override var bottomDialog: Int
                        get() = host.bottomDialog
                        set(value) {
                            host.bottomDialog = value
                        }
                }
            )
        }
    }

    @Composable
    override fun Content() {
        BookMemoRoute(
            model,
            images,
            { requireContext().openUrl(it) },
            { isAdded && !parentFragmentManager.isStateSaved },
            ::dismissAllowingStateLoss,
            { cancelable, outside ->
                isCancelable = cancelable
                dialog?.setCanceledOnTouchOutside(outside)
            },
        )
    }

    override fun onStop() {
        lifecycleScope.launch { model.flushDraft() }
        super.onStop()
    }

    override fun onDestroyView() {
        visibility.release()
        super.onDestroyView()
    }

    override fun onCancel(dialog: DialogInterface) {
        model.close()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        visibility.release()
        super.onDismiss(dialog)
    }
}

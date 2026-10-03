package io.legado.app.ui.book.read

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.AppManualReplacementRepository
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout

/** Selects replacement rules for the current book or a source import preview. */
class ManualReplaceRulesDialog() : BaseComposeDialogFragment() {
    constructor(ids: List<Long>, requestId: String?) : this() {
        arguments =
            Bundle().apply {
                putBoolean("sourceReplacement", true)
                putLongArray("selectedIds", ids.toLongArray())
                putString("requestId", requestId)
            }
    }

    interface Callback {
        fun onManualSourceRulesSelected(ids: List<Long>, requestId: String?)
    }

    private val sourceReplacement
        get() = arguments?.getBoolean("sourceReplacement") == true

    private val reader by activityViewModels<ReadBookViewModel>()
    private val model by
        viewModels<ManualReplacementViewModel> {
            viewModelFactory {
                initializer {
                    ManualReplacementViewModel(
                        AppManualReplacementRepository(),
                        createSavedStateHandle(),
                        sourceReplacement,
                        arguments?.getLongArray("selectedIds")?.toList().orEmpty(),
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!sourceReplacement && arguments?.containsKey("readerBookUrl") != true) {
            arguments =
                (arguments ?: Bundle()).apply {
                    putString("readerBookUrl", ReadBook.book?.bookUrl)
                    putLongArray(
                        "selectedIds",
                        ReadBook.book?.config?.manualReplaceRuleIds.orEmpty().toLongArray(),
                    )
                }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(.9f, .9f)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (
            !sourceReplacement &&
                (ReadBook.book == null ||
                    ReadBook.book?.bookUrl != arguments?.getString("readerBookUrl"))
        )
            model.cancel()
    }

    @Composable
    override fun Content() {
        ManualReplacementRoute(
            model,
            { isAdded && !parentFragmentManager.isStateSaved },
            ::applySelection,
            ::dismissAllowingStateLoss,
        )
    }

    private fun applySelection(ids: List<Long>) {
        if (sourceReplacement)
            (parentFragment as? Callback)?.onManualSourceRulesSelected(
                ids,
                arguments?.getString("requestId"),
            )
        else
            ReadBook.book
                ?.takeIf { it.bookUrl == arguments?.getString("readerBookUrl") }
                ?.let { book ->
                    book.config.manualReplaceRuleIds = ids
                    ReadBook.saveRead()
                    reader.replaceRuleChanged()
                }
    }

    override fun onCancel(dialog: DialogInterface) {
        model.cancel()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (activity?.isChangingConfigurations != true) model.cancel()
        super.onDismiss(dialog)
    }
}

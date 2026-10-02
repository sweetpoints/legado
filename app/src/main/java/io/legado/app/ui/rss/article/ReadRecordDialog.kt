package io.legado.app.ui.rss.article

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.repository.RoomRssReadRecordRepository
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout

class ReadRecordDialog() : BaseComposeDialogFragment() {
    constructor(origin: String?) : this() { arguments = Bundle().apply { putString("origin", origin) } }
    internal val model by viewModels<RssReadRecordViewModel> {
        viewModelFactory { initializer { RssReadRecordViewModel(RoomRssReadRecordRepository(), createSavedStateHandle(), arguments?.getString("origin")) } }
    }
    override fun onStart() { super.onStart(); setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() {
        RssReadRecordRoute(model, { isAdded && !parentFragmentManager.isStateSaved },
            { ReadRss.readRss(requireActivity() as AppCompatActivity, it) },
            { requireContext().openUrl(it) }, ::dismissAllowingStateLoss, { isCancelable = it })
    }
    override fun onCancel(dialog: DialogInterface) { model.cancel(); super.onCancel(dialog) }
    interface OnRecordClickListener { fun onRecordClick(record: RssReadRecord?) }
}

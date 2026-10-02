package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.preferences.PreferenceWordCountFilterRepository
import io.legado.app.help.config.AppConfig

internal fun Menu.syncChangeSourceResultOptions() {
    findItem(R.id.menu_load_word_count)?.isChecked = AppConfig.changeSourceLoadWordCount
    findItem(R.id.menu_sort_respond_time)?.isChecked = AppConfig.changeSourceSortRespondTime
    findItem(R.id.menu_word_count_filter)?.isChecked = AppConfig.changeSourceWordCountFilterMode != ChangeSourceResultOptions.FILTER_OFF
}
interface ChangeSourceWordCountFilterCallback { fun onWordCountFilterChanged(reloadMeasurements: Boolean) }
internal fun Fragment.showChangeSourceWordCountFilter() {
    if (childFragmentManager.findFragmentByTag("wordCountFilter") == null) ChangeSourceWordCountFilterDialog().show(childFragmentManager, "wordCountFilter")
}
class ChangeSourceWordCountFilterDialog : BaseComposeDialogFragment() {
    private val model by viewModels<WordCountFilterViewModel> {
        viewModelFactory { initializer { WordCountFilterViewModel(PreferenceWordCountFilterRepository(requireContext()), createSavedStateHandle()) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() { super.onStart(); dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    @Composable override fun Content() { WordCountFilterRoute(model,
        { (parentFragment as? ChangeSourceWordCountFilterCallback)?.onWordCountFilterChanged(it) }, ::dismissAllowingStateLoss) }
}

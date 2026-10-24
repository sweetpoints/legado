package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.repository.RoomHighlightGroupRepository
import io.legado.app.model.ReadBook
import io.legado.app.utils.resizeForIme
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import io.legado.app.utils.launchCleanup

class HighlightGroupManageDialog : BaseDialogFragment(0) {
    internal val model by viewModels<HighlightGroupViewModel> {
        viewModelFactory { initializer { HighlightGroupViewModel(RoomHighlightGroupRepository(), createSavedStateHandle(), FileHighlightGroupDraftStore(requireContext())) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE, 0); isCancelable = false }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = FrameLayout(requireContext()).apply { id = R.id.highlight_groups_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        model.pauseObservation()
        if (childFragmentManager.findFragmentByTag("highlight-groups-ui") == null)
            childFragmentManager.beginTransaction().add(R.id.highlight_groups_flutter_content, HighlightGroupsFlutterFragment(), "highlight-groups-ui").commitNow()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.observe()
                try { awaitCancellation() } finally { model.pauseObservation() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { s ->
                    if (s.refresh && isAdded && !parentFragmentManager.isStateSaved) {
                        try {
                            if (model.claimRefresh { isResumed && isAdded && !parentFragmentManager.isStateSaved }) ReadBook.upHighlightRules()
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (error: Exception) { if (isAdded) requireContext().toastOnUi(error.localizedMessage ?: error.javaClass.simpleName) }
                    }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart(); setLayout(0.9f, 0.9f); dialog?.window?.resizeForIme()
        dialog?.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) requestBackFromHost(); true } else false
        }
    }
    override fun onResume() {
        super.onResume(); view?.post { if (isResumed) (childFragmentManager.findFragmentByTag("highlight-groups-ui") as? HighlightGroupsFlutterFragment)?.onPostResume() }
    }
    internal fun requestBackFromHost() {
        val fragment = childFragmentManager.findFragmentByTag("highlight-groups-ui") as? HighlightGroupsFlutterFragment
        if (fragment?.flutterEngine != null) fragment.onBackPressed() else cancelOrClose()
    }
    internal fun cancelOrClose() = highlightGroupsCommand(model, "back", emptyMap<String, Any?>(), ::finishGroups)
    internal fun finishGroups() { if (!model.state.value.busy && !model.state.value.refresh) { model.close(); dismissAllowingStateLoss() } }
    override fun onStop() { lifecycleScope.launchCleanup { runCatching { model.flush() } }; super.onStop() }
    override fun dismiss() { requestBackFromHost() }
}

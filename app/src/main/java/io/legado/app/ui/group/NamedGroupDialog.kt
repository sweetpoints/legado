package io.legado.app.ui.group

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.repository.NamedGroupRepository
import io.legado.app.utils.resizeForIme
import io.legado.app.utils.setLayout

/** Shared UI engine, with repository and kind fixed by the normal native entry point. */
abstract class NamedGroupDialog : BaseDialogFragment(0) {
    protected abstract val groupKind: String
    protected abstract fun repository(): NamedGroupRepository
    internal val model by viewModels<NamedGroupViewModel> {
        viewModelFactory { initializer {
            val saved = createSavedStateHandle()
            NamedGroupViewModel(repository(), saved, NamedGroupSession(requireContext().applicationContext, saved))
        } }
    }
    internal val coordinator by lazy { NamedGroupNativeCoordinator(model, lifecycle, groupKind,
        { isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved },
        ::dismissAllowingStateLoss) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setStyle(STYLE_NO_TITLE, 0); isCancelable = false
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).apply { id = R.id.named_groups_flutter_content }
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
        coordinator
        if (childFragmentManager.findFragmentByTag(TAG) == null)
            childFragmentManager.beginTransaction().add(R.id.named_groups_flutter_content,
                NamedGroupsFlutterFragment(), TAG).commitNow()
    }
    override fun onStart() {
        super.onStart(); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Keep this independent floating Dialog window non-fullscreen and frame-fitted.
        // NamedGroupsPage consumes the already-fitted bottom IME inset at its own boundary;
        // switching this host to a fullscreen/edge-to-edge window requires changing that policy.
        dialog?.window?.apply { setBackgroundDrawableResource(R.color.transparent); resizeForIme() }
        dialog?.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) requestClose()
                true
            } else false
        }
    }
    override fun onResume() {
        super.onResume()
        view?.post { if (isResumed) fragment()?.onPostResume() }
    }
    override fun dismiss() { requestClose() }
    private fun fragment() = childFragmentManager.findFragmentByTag(TAG) as? NamedGroupsFlutterFragment
    private fun requestClose() {
        val fragment = fragment()
        if (fragment?.flutterEngine?.dartExecutor?.isExecutingDart == true) fragment.onBackPressed()
        else coordinator.requestClose()
    }
    override fun onDestroy() { coordinator.close(); super.onDestroy() }
    private companion object { const val TAG = "named-groups-flutter" }
}

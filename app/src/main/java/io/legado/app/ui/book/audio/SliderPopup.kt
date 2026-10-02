package io.legado.app.ui.book.audio

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.model.AudioPlay
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.applyMd3PopupStyle

class SliderPopup(private val context: Context, private val name: Int) :
    PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    companion object { const val TIMER = 1; const val SPEED = 2 }
    private val controller = AudioSliderController(if (name == TIMER) AudioSliderMode.Timer else AudioSliderMode.Speed) {
        if (name == TIMER) AudioPlay.setTimer(it.toInt()) else AudioPlay.setSpeed(it)
    }
    private val compose = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent { LegadoComposeTheme {
            val state by controller.state.collectAsStateWithLifecycle()
            AudioSliderScreen(state, controller::user)
        } }
    }
    private var owner: LifecycleOwner? = null
    private val observer: LifecycleEventObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) {
        dismiss(); compose.disposeComposition(); owner?.lifecycle?.removeObserver(observer); owner = null
    } }
    init {
        contentView = compose; applyMd3PopupStyle()
        isTouchable = true; isOutsideTouchable = false; isFocusable = true
        setOnDismissListener { compose.disposeComposition() }
        (context as? LifecycleOwner)?.let { owner = it; it.lifecycle.addObserver(observer) }
        refresh()
    }
    private fun refresh() { controller.refresh(if (name == TIMER) AudioPlayService.timeMinute.toFloat() else AudioPlayService.playSpeed) }
    private fun prepare(anchor: View?) {
        val host = requireNotNull(anchor?.findViewTreeLifecycleOwner() ?: context as? LifecycleOwner)
        if (owner !== host) { owner?.lifecycle?.removeObserver(observer); owner = host; host.lifecycle.addObserver(observer) }
        compose.setViewTreeLifecycleOwner(host)
        compose.setViewTreeSavedStateRegistryOwner(anchor?.findViewTreeSavedStateRegistryOwner() ?: context as? SavedStateRegistryOwner)
        refresh()
    }
    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        prepare(anchor); super.showAsDropDown(anchor, xoff, yoff, gravity)
    }
    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        prepare(parent); super.showAtLocation(parent, gravity, x, y)
    }
}

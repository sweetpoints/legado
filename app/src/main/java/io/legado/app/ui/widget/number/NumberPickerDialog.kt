package io.legado.app.ui.widget.number

import io.legado.app.utils.resizeForIme

import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.setLayout

/** Keeps the fluent caller API while each show owns an independent Compose dialog lifecycle. */
class NumberPickerDialog(private val context: Context, private val isDecimalMode: Boolean = false) {
    private var title = ""
    private var maximum = 0
    private var minimum = 0
    private var initial = 0
    private var labels: List<String>? = null
    private var customTextId: Int? = null
    private var customListener: (() -> Unit)? = null

    fun setTitle(title: String): NumberPickerDialog = apply { this.title = title }

    fun setMaxValue(value: Int): NumberPickerDialog = apply { maximum = value }

    fun setMinValue(value: Int): NumberPickerDialog = apply { minimum = value }

    fun setValue(value: Int): NumberPickerDialog = apply { initial = value }

    fun setDisplayedValues(values: Array<String>): NumberPickerDialog = apply {
        labels = values.toList()
    }

    fun setCustomButton(textId: Int, listener: (() -> Unit)?): NumberPickerDialog = apply {
        customTextId = textId
        customListener = listener
    }

    fun show(callBack: ((value: Int) -> Unit)?) {
        val config = NumberPickerConfig(title, minimum, maximum, initial, isDecimalMode, labels)
        val customLabel = customTextId?.let(context::getString)
        val neutral = customListener
        val owner = generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext?.takeIf { it !== current }
        }.filterIsInstance<LifecycleOwner>().firstOrNull()
        if (owner?.lifecycle?.currentState == Lifecycle.State.DESTROYED) return
        val dialog = ComponentDialog(context)
        val content = ComposeView(context)
        lateinit var ownerObserver: DefaultLifecycleObserver
        fun closeDialog() {
            owner?.lifecycle?.removeObserver(ownerObserver)
            if (dialog.window?.decorView?.isAttachedToWindow == true) dialog.dismiss()
            else content.disposeComposition()
        }
        ownerObserver = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = closeDialog()
        }
        dialog.setOnDismissListener {
            owner?.lifecycle?.removeObserver(ownerObserver)
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(
            content.apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        NumberPickerRoute(
                            config,
                            customLabel,
                            { if (isAttachedToWindow) callBack?.invoke(it) },
                            { if (isAttachedToWindow) neutral?.invoke() },
                            ::closeDialog,
                        )
                    }
                }
            }
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        owner?.lifecycle?.addObserver(ownerObserver)
        dialog.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            resizeForIme()
        }
        dialog.setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

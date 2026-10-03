package io.legado.app.ui.widget.number

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
        val dialog = ComponentDialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                setContent {
                    LegadoComposeTheme {
                        NumberPickerRoute(
                            config,
                            customLabel,
                            { callBack?.invoke(it) },
                            { neutral?.invoke() },
                            dialog::dismiss,
                        )
                    }
                }
            }
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

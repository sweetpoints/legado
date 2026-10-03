@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.widget.toast.ToastComposePresentation

private var toastSession: CustomToastSession? = null

private var toastLegacy: Toast? = null

fun Context.toastOnUi(message: Int, duration: Int = Toast.LENGTH_SHORT) {
    toastOnUi(getString(message), duration)
}

@SuppressLint("ShowToast")
fun Context.toastOnUi(message: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
    runOnUI {
        kotlin.runCatching {
            toastSession?.cancel()
            toastSession = null
            val session =
                CustomToastSession(this, message, duration) { closed ->
                    if (toastSession === closed) toastSession = null
                }
            toastSession = session
            try {
                session.show()
            } catch (error: Throwable) {
                session.cancel()
                throw error
            }
        }
    }
}

fun Context.toastOnUiLegacy(message: CharSequence) {
    runOnUI {
        kotlin.runCatching {
            if (toastLegacy == null || BuildConfig.DEBUG || AppConfig.recordLog) {
                toastLegacy = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = Toast.LENGTH_SHORT
            }
            toastLegacy?.show()
        }
    }
}

fun Context.longToastOnUi(message: Int) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUi(message: CharSequence?) {
    toastOnUi(message, Toast.LENGTH_LONG)
}

fun Context.longToastOnUiLegacy(message: CharSequence) {
    runOnUI {
        kotlin.runCatching {
            if (toastLegacy == null || BuildConfig.DEBUG || AppConfig.recordLog) {
                toastLegacy = Toast.makeText(this, message, Toast.LENGTH_LONG)
            } else {
                toastLegacy?.setText(message)
                toastLegacy?.duration = Toast.LENGTH_LONG
            }
            toastLegacy?.show()
        }
    }
}

fun Fragment.toastOnUi(message: Int) = requireActivity().toastOnUi(message)

fun Fragment.toastOnUi(message: CharSequence) = requireActivity().toastOnUi(message)

fun Fragment.longToast(message: Int) = requireContext().longToastOnUi(message)

fun Fragment.longToast(message: CharSequence) = requireContext().longToastOnUi(message)

private class CustomToastSession(
    context: Context,
    message: CharSequence?,
    private val duration: Int,
    private val onClosed: (CustomToastSession) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val toast = Toast(context)
    private val backgroundColor = context.bottomBackground
    private val textColor = context.getPrimaryTextColor(ColorUtils.isColorLight(backgroundColor))
    private val metrics = context.resources.displayMetrics
    private val toastMessage =
        message.toToastMessage(
            baseTextSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics),
            scaledDensity = metrics.scaledDensity,
            color = textColor,
        )
    private val presentation =
        ToastComposePresentation(
            context = context,
            message = toastMessage,
            backgroundColor = backgroundColor,
            textColor = textColor,
        )
    private var closed = false
    private val cleanup = Runnable { close() }
    private var toastCallbackRegistration: AutoCloseable? = null

    init {
        @Suppress("DEPRECATION") run { toast.view = presentation.view }
        toast.duration = duration
        toastCallbackRegistration =
            runToastCallbackOnApi30(Build.VERSION.SDK_INT) {
                ToastCallbackApi30.registerHiddenCallback(toast, ::close)
            }
    }

    fun show() {
        toast.show()
        presentation.onShown()
        val timeoutMillis = if (duration == Toast.LENGTH_LONG) 4_000L else 2_500L
        handler.postDelayed(cleanup, timeoutMillis)
    }

    fun cancel() {
        try {
            toast.cancel()
        } finally {
            close()
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(cleanup)
        runCatching { toastCallbackRegistration?.close() }
        toastCallbackRegistration = null
        presentation.close()
        onClosed(this)
    }
}

internal fun <T> runToastCallbackOnApi30(sdkInt: Int, register: () -> T): T? =
    if (sdkInt >= 30) register() else null

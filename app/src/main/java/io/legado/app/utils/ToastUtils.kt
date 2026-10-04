@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import io.legado.app.BuildConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.widget.toast.ToastComposePresentation
import io.legado.app.ui.widget.toast.ToastSessionTimeouts

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
    private val toast = Toast(context)
    private val backgroundColor = context.bottomBackground
    private val textColor = context.getPrimaryTextColor(ColorUtils.isColorLight(backgroundColor))
    private val metrics = context.resources.displayMetrics
    private val composeDensity = Density(context)
    private val toastMessage =
        message.toToastMessage(
            baseTextSizePx = with(composeDensity) { 16.sp.toPx() },
            density = composeDensity,
            color = textColor,
        )
    private val presentation =
        ToastComposePresentation(
            context = context,
            message = toastMessage,
            backgroundColor = backgroundColor,
            textColor = textColor,
            onAttached = { timeouts.onAttached(duration) },
        )
    private var closed = false
    private val timeouts = ToastSessionTimeouts(::cancel)
    private var toastCallbackRegistration: AutoCloseable? = null

    init {
        @Suppress("DEPRECATION") run { toast.view = presentation.view }
        toast.duration = duration
        toastCallbackRegistration =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ToastCallbackApi30.registerHiddenCallback(toast, ::close)
            } else null
    }

    fun show() {
        toast.show()
        timeouts.startPendingAttachmentTimeout()
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
        timeouts.close()
        runCatching { toast.cancel() }
        runCatching { toastCallbackRegistration?.close() }
        toastCallbackRegistration = null
        presentation.close()
        onClosed(this)
    }
}

internal fun <T> runToastCallbackOnApi30(sdkInt: Int, register: () -> T): T? =
    if (sdkInt >= 30) register() else null

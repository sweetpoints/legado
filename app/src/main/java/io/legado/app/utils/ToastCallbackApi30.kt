package io.legado.app.utils

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi

/** Keeps Toast.Callback references out of the code loaded by pre-30 devices. */
@RequiresApi(Build.VERSION_CODES.R)
internal object ToastCallbackApi30 {
    fun registerHiddenCallback(toast: Toast, onHidden: () -> Unit): AutoCloseable {
        val callback =
            object : Toast.Callback() {
                override fun onToastHidden() {
                    onHidden()
                }
            }
        toast.addCallback(callback)

        var registered = true
        return AutoCloseable {
            if (registered) {
                registered = false
                toast.removeCallback(callback)
            }
        }
    }
}

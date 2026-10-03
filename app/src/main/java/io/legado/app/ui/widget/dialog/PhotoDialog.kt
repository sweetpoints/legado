package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.dialog.photo.GlidePhotoImageLoader
import io.legado.app.ui.widget.dialog.photo.PhotoRequest
import io.legado.app.ui.widget.dialog.photo.PhotoRoute
import io.legado.app.utils.setLayout

/** Fullscreen Compose image viewer, including restored src/sourceOrigin/isBook arguments. */
class PhotoDialog() : BaseComposeDialogFragment() {
    constructor(src: String, sourceOrigin: String? = null, isBook: Boolean = false) : this() {
        arguments =
            Bundle().apply {
                putString("src", src)
                putString("sourceOrigin", sourceOrigin)
                putBoolean("isBook", isBook)
            }
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent)
            setDimAmount(0f)
        }
    }

    @Composable
    override fun Content() {
        val loader = remember { GlidePhotoImageLoader(requireContext()) }
        val request =
            remember(arguments) {
                PhotoRequest(
                    arguments?.getString("src").orEmpty(),
                    arguments?.getString("sourceOrigin"),
                    arguments?.getBoolean("isBook") == true,
                )
            }
        PhotoRoute(request, loader, ::dismiss, AppConfig.isEInkMode)
    }
}

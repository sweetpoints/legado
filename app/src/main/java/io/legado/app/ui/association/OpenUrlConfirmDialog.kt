package io.legado.app.ui.association

import io.legado.app.data.repository.DefaultOpenUrlSourceRepository

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class OpenUrlConfirmDialog() : BaseComposeDialogFragment() {
    constructor(
        uri: String,
        mimeType: String?,
        sourceOrigin: String? = null,
        sourceName: String? = null,
        sourceType: Int,
    ) : this() {
        arguments = Bundle().apply {
            putString("uri", uri)
            putString("mimeType", mimeType)
            putString("sourceOrigin", sourceOrigin)
            putString("sourceName", sourceName)
            putInt("sourceType", sourceType)
        }
    }

    private val viewModel by viewModels<OpenUrlConfirmViewModel> {
        viewModelFactory {
            initializer { OpenUrlConfirmViewModel(createSavedStateHandle(), DefaultOpenUrlSourceRepository()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // BaseDialogFragment only finishes a dismissed host when it is not being recreated.
        arguments = (arguments ?: Bundle()).apply { putBoolean("finishOnDismiss", true) }
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        OpenUrlConfirmRoute(
            viewModel = viewModel,
            onOpenUrl = ::openUrl,
            onClose = ::dismiss,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
        )
    }

    private fun openUrl(url: String, mimeType: String?) {
        try {
            val targetIntent = createOpenUrlIntent(url, mimeType)
            if (targetIntent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(targetIntent)
            } else {
                toastOnUi(R.string.can_not_open)
            }
        } catch (error: Exception) {
            AppLog.put("打开链接失败", error, true)
        }
    }
}

internal fun createOpenUrlIntent(url: String, mimeType: String?): Intent = Intent(Intent.ACTION_VIEW).apply {
    if (!mimeType.isNullOrBlank()) setDataAndType(url.toUri(), mimeType) else data = url.toUri()
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

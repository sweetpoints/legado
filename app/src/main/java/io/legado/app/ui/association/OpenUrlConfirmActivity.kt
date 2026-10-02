package io.legado.app.ui.association

import android.os.Bundle
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.SourceType
import io.legado.app.constant.Theme
import io.legado.app.utils.showDialogFragment

/** Transparent host; FragmentManager restores the existing dialog on recreation. */
class OpenUrlConfirmActivity : BaseComposeActivity(theme = Theme.Transparent, imageBg = false) {
    @Composable
    override fun Content(savedInstanceState: Bundle?) = Unit

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        intent.getStringExtra("uri")?.let {
            showDialogFragment(OpenUrlConfirmDialog(
                it,
                intent.getStringExtra("mimeType"),
                intent.getStringExtra("sourceOrigin"),
                intent.getStringExtra("sourceName"),
                intent.getIntExtra("sourceType", SourceType.book),
            ))
        } ?: finish()
    }
}

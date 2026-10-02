package io.legado.app.ui.association

import android.os.Bundle
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.SourceType
import io.legado.app.constant.Theme
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.utils.isMainThread
import io.legado.app.utils.showDialogFragment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/** Transparent Compose host for the request-scoped verification dialog. */
class VerificationCodeActivity : BaseComposeActivity(theme = Theme.Transparent, transparent = true, imageBg = false) {
    private val verificationResultKey: String?
        get() = intent.getStringExtra("verificationResultKey")

    @Composable
    override fun Content(savedInstanceState: Bundle?) = Unit

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (!SourceVerificationHelp.attachVerificationUi(verificationResultKey, ::finishVerificationUi)) {
            finish()
            return
        }
        // FragmentManager restores an existing dialog and its ViewModel itself.
        if (supportFragmentManager.fragments.any { it is VerificationCodeDialog }) return
        val imageUrl = intent.getStringExtra("imageUrl")
        if (imageUrl == null) {
            SourceVerificationHelp.checkResult(verificationResultKey)
            finish()
            return
        }
        showDialogFragment(
            VerificationCodeDialog(
                imageUrl,
                intent.getStringExtra("sourceOrigin"),
                intent.getStringExtra("sourceName"),
                intent.getIntExtra("sourceType", SourceType.book),
                verificationResultKey,
            )
        )
    }

    private fun finishVerificationUi() {
        if (isMainThread) {
            finish()
            return
        }
        val finished = CountDownLatch(1)
        runOnUiThread {
            try { finish() } finally { finished.countDown() }
        }
        finished.await(5, SECONDS)
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            SourceVerificationHelp.checkResult(verificationResultKey)
        }
        super.onDestroy()
    }
}

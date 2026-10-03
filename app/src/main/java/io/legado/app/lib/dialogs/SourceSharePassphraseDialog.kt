package io.legado.app.lib.dialogs

import android.content.DialogInterface
import android.view.LayoutInflater
import io.legado.app.R
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase

@Suppress("UNUSED_PARAMETER")
fun AlertBuilder<DialogInterface>.sourceSharePassphraseButton(
    layoutInflater: LayoutInflater,
    url: String,
    type: SourceSharePassphrase.Type,
) {
    if (!SourceSharePassphrase.canEncode(url)) return
    neutralButton(R.string.shibboleth) {
        val passphrase =
            SourceSharePassphrase.encode(
                url = url,
                type = type,
                expiryDays = DirectLinkUpload.getExpiryDate(),
            )
        SourceSharePassphraseComposeDialog(ctx, passphrase).show()
    }
}

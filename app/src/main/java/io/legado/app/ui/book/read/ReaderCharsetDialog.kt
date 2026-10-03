package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppConst.charsets
import io.legado.app.model.ReadBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.setLayout

/**
 * Only a charset draft and a compact owner digest are saved; the book remains in the reader engine.
 */
class ReaderCharsetDialog : BaseComposeDialogFragment() {
    override fun onStart() {
        super.onStart()
        setLayout(.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun Content() {
        ReaderCharsetScreen(
            initial = arguments?.getString(ARG_CHARSET).orEmpty(),
            confirm = { charset ->
                val expectedOwner = arguments?.getString(ARG_OWNER)
                val currentOwner = ReadBook.book?.bookUrl?.let(MD5Utils::md5Encode)
                if (
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        currentOwner == expectedOwner
                ) {
                    ReadBook.setCharset(charset)
                }
                dismiss()
            },
            cancel = { dismiss() },
        )
    }

    companion object {
        private const val ARG_OWNER = "owner"
        private const val ARG_CHARSET = "charset"

        fun create(): ReaderCharsetDialog =
            ReaderCharsetDialog().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_OWNER, ReadBook.book?.bookUrl?.let(MD5Utils::md5Encode))
                        putString(ARG_CHARSET, ReadBook.book?.charset)
                    }
            }
    }
}

@Composable
internal fun ReaderCharsetScreen(initial: String, confirm: (String) -> Unit, cancel: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    var choicesExpanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(20.dp)) {
        Text(stringResource(R.string.set_charset))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("reader-charset"),
            label = { Text("charset") },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    { choicesExpanded = !choicesExpanded },
                    Modifier.testTag("reader-charset-choices"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_down),
                        stringResource(R.string.set_charset),
                    )
                }
                DropdownMenu(choicesExpanded, { choicesExpanded = false }) {
                    charsets.forEach { charset ->
                        DropdownMenuItem(
                            text = { Text(charset) },
                            onClick = {
                                draft = charset
                                choicesExpanded = false
                            },
                        )
                    }
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(cancel) { Text(stringResource(android.R.string.cancel)) }
            TextButton({ confirm(draft) }, Modifier.testTag("reader-charset-confirm")) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

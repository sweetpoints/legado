package io.legado.app.ui.widget.dialog

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.widget.dialog.textlist.TextListScreen
import io.legado.app.ui.widget.dialog.textlist.TextListUiState
import io.legado.app.utils.setLayout

@Suppress("unused")
class TextListDialog() : BaseComposeDialogFragment() {
    constructor(title: String, values: ArrayList<String>) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putStringArrayList("values", ArrayList(values))
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    @Composable override fun Content() {
        val state = remember(arguments) {
            TextListUiState.from(arguments?.getString("title").orEmpty(),
                arguments?.getStringArrayList("values")?.toList().orEmpty())
        }
        TextListScreen(state, ::dismiss)
    }
}

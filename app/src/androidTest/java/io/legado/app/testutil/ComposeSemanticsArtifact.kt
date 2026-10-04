package io.legado.app.testutil

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import java.io.File

internal fun SemanticsNodeInteractionsProvider.saveSemantics(context: Context, name: String) {
    val tree = onAllNodes(isRoot(), useUnmergedTree = true).printToString(Int.MAX_VALUE)
    File(context.getExternalFilesDir("ui-regression"), "$name-semantics.txt").apply {
        requireNotNull(parentFile).mkdirs()
        writeText(tree)
    }
}

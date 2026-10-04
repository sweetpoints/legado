package io.legado.app.ui.association.compose

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.association.AssociationPhase
import io.legado.app.utils.FileDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Confirmation reads its full source from the owning host's private UUID session. */
open class AssociationDataImportDialog() : BaseComposeDialogFragment() {
    private var legacyRequest: Pair<String, String>? = null

    constructor(type: String, source: String) : this() {
        // Compatibility input is transient until the owner accepts it. Never place source in args.
        initializeLegacyRequest(type, source)
    }

    protected fun initializeLegacyRequest(type: String, source: String) {
        legacyRequest = type to source
    }

    protected fun initializeSession(ticket: String) {
        arguments = Bundle().apply { putString(TICKET_KEY, ticket) }
    }

    private val model: AssociationImportViewModel
        get() = (requireActivity() as AssociationComposeActivity).importModel

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (arguments?.getString(TICKET_KEY) == null) {
            val ticket = model.ownedTicket
            checkNotNull(ticket) { "No private import owner" }
            arguments = Bundle().apply { putString(TICKET_KEY, ticket) }
        }
    }

    @Composable
    override fun Content() {
        val state by model.state.collectAsStateWithLifecycle()
        val session = state.session
        val ownerMatches = arguments?.getString(TICKET_KEY) == state.ticket
        val legacy = legacyRequest
        val sourceMatches =
            legacy == null ||
                (legacy.first == session?.importType && legacy.second == session.importSource)
        var filename by remember(state.ticket, session?.importSource) { mutableStateOf("") }
        LaunchedEffect(state.ticket, session?.importSource) {
            session?.importSource?.let { source ->
                filename =
                    withContext(Dispatchers.IO) { FileDoc.fromUri(Uri.parse(source), false).name }
            }
        }
        val importing = state.busy
        isCancelable = !importing
        LaunchedEffect(state.loaded, ownerMatches, sourceMatches, session?.phase) {
            if (state.loaded && (!ownerMatches || !sourceMatches)) dismissAllowingStateLoss()
            if (session?.phase == AssociationPhase.Finished) dismissAllowingStateLoss()
        }
        if (!ownerMatches || !sourceMatches || session == null) return
        val backup = session.importType == "backup"
        AssociationDataImportScreen(
            backup = backup,
            filename = filename,
            importing = importing,
            onConfirm = { model.confirmOperation(checkNotNull(session.importType)) },
            onClose = ::dismiss,
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }

    companion object {
        private const val TICKET_KEY = "association.confirm.ticket"

        fun fromSession(ticket: String) =
            AssociationDataImportDialog().apply {
                arguments = Bundle().apply { putString(TICKET_KEY, ticket) }
            }
    }
}

@Composable
fun AssociationDataImportScreen(
    backup: Boolean,
    filename: String,
    importing: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!importing) onClose() },
        title = {
            Text(
                stringResource(
                    if (backup) R.string.restore_confirmation else R.string.import_bookshelf
                )
            )
        },
        text = {
            Text(
                when {
                    importing -> stringResource(R.string.importing)
                    backup -> "$filename\n${stringResource(R.string.restore_message)}"
                    else -> filename
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !importing) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !importing) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

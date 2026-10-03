package io.legado.app.data.association

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Capture the original public Intent contract before replacing host extras with a private UUID. */
fun associationLaunchInput(intent: Intent, host: AssociationHostKind): AssociationInput {
    if (host == AssociationHostKind.Online) {
        return AssociationInput(
            host,
            AssociationInputKind.View,
            intent.data?.let { listOf(it.toString()) }.orEmpty(),
            intentFlags = intent.flags,
        )
    }
    return when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE ->
            AssociationInput(
                host,
                AssociationInputKind.SharedUris,
                uris =
                    IntentCompat.getParcelableArrayListExtra(
                            intent,
                            Intent.EXTRA_STREAM,
                            Uri::class.java,
                        )
                        .orEmpty()
                        .map { it.toString() },
                mimeType = intent.type,
                intentFlags = intent.flags,
            )
        Intent.ACTION_SEND -> sharedInput(intent, host)
        Intent.ACTION_VIEW ->
            AssociationInput(
                host,
                AssociationInputKind.View,
                intent.data?.let { listOf(it.toString()) }.orEmpty(),
                intentFlags = intent.flags,
            )
        else -> AssociationInput(host, AssociationInputKind.Invalid, intentFlags = intent.flags)
    }
}

private fun sharedInput(intent: Intent, host: AssociationHostKind): AssociationInput {
    if (!associationSupportedSharedImportMimeType(intent.type))
        return AssociationInput(
            host,
            AssociationInputKind.Invalid,
            mimeType = intent.type,
            intentFlags = intent.flags,
        )
    val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    if (uri != null)
        return AssociationInput(
            host,
            AssociationInputKind.SharedUri,
            listOf(uri.toString()),
            mimeType = intent.type,
            intentFlags = intent.flags,
        )
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    return AssociationInput(
        host,
        if (text.isNullOrBlank()) AssociationInputKind.Invalid else AssociationInputKind.SharedText,
        text = text,
        mimeType = intent.type,
        intentFlags = intent.flags,
    )
}

class AssociationLaunchRepository(
    context: Context,
    private val sessions: AssociationSessionRepository = FileAssociationSessionRepository(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun prepare(intent: Intent, host: AssociationHostKind): String {
        var allocatedTicket: String? = null
        try {
            val ticket =
                withContext(dispatcher) {
                    sessions.create(associationLaunchInput(intent, host)).also {
                        allocatedTicket = it
                    }
                }
            currentCoroutineContext().ensureActive()
            return ticket
        } catch (failure: Throwable) {
            // The outer dispatcher hop also owns delivery. Inner create can complete while
            // cancellation prevents the host from receiving the UUID; this owner cleans it up.
            allocatedTicket?.let { ticket ->
                withContext(NonCancellable) { sessions.release(ticket) }
            }
            throw failure
        }
    }
}

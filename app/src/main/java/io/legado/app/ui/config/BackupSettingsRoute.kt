package io.legado.app.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.BackupLanImageRepository
import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

@Composable internal fun BackupSettingsRoute(model: BackupSettingsViewModel, controller: BackupOperationsController, images: BackupLanImageRepository,
    ready: () -> Boolean, host: (BackupHostEvent) -> Unit, success: () -> Unit, truncated: () -> Unit,
    search: String? = null, searchFinished: () -> Unit = {}, searchEmpty: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle(); val runtime by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready); val handle by rememberUpdatedState(host)
    val successToast by rememberUpdatedState(success); val warningToast by rememberUpdatedState(truncated)
    LaunchedEffect(model, controller, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        combine(model.state, controller.state) { editor, tasks -> editor to tasks }.collect { (editor, tasks) ->
            if (available() && !editor.loading && !editor.failed) {
                tasks.event?.let { if (controller.consumeEvent(it.id)) handle(it) }
                tasks.success?.let { if (controller.consumeSuccess(it)) successToast() }
                if (tasks.truncatedCloudListing && controller.consumeTruncatedNotice()) warningToast()
            }
        }
    } }
    var image by remember(runtime.offer?.id) { mutableStateOf<ImageBitmap?>(null) }
    var imageError by remember(runtime.offer?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime.offer?.id, images) {
        runtime.offer?.let { offer -> try { val value = images.load(offer.imagePath); currentCoroutineContext().ensureActive(); image = value.asImageBitmap() }
            catch (canceled: CancellationException) { throw canceled }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); imageError = error.localizedMessage.orEmpty() } }
    }
    BackHandler(runtime.busy || runtime.pendingReceipt || state.busy || state.pendingCommit) { if (runtime.busy) controller.cancel() }
    val editor = BackupEditorActions(model::boolean, { form -> when (form) {
        BackupForm.Content -> model.choicesForm(BackupChoiceGroup.Content); BackupForm.Ignore -> model.choicesForm(BackupChoiceGroup.Ignore)
        BackupForm.Automatic -> model.automaticForm(); BackupForm.LocalPassword -> model.localPasswordForm()
        else -> model.textForm(BackupSettingText.valueOf(form.name))
    } }, model::text, model::automaticEnabled, model::automaticWebDav, model::interval, model::choice, model::confirm, model::dismiss, model::retry)
    val tasks = BackupTaskActions(controller::pathMenu, controller::backup, controller::restore, controller::localRestore,
        controller::lanMenu, controller::help, controller::log, controller::importOld, controller::selectPath, controller::defaultPath,
        controller::manualDestination, controller::sendConfirm, controller::send, controller::scanLan, controller::receiveConfirmed,
        controller::selectRestore, controller::dismissPopup, controller::cancel, controller::retry, controller::retryConfirmed, controller::closeOffer)
    BackupSettingsScreen(state, runtime, editor, tasks, image, imageError, search, searchFinished, searchEmpty)
}

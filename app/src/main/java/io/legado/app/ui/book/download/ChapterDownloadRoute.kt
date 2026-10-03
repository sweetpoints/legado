package io.legado.app.ui.book.download

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable fun ChapterDownloadRoute(model:ChapterDownloadViewModel,onDownload:(ChapterDownloadDelivery)->Unit,
    onClose:()->Unit,onError:(Throwable)->Unit,available:()->Boolean={true}) {
    val state by model.state.collectAsStateWithLifecycle();val owner=LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onDownload);val close by rememberUpdatedState(onClose)
    val error by rememberUpdatedState(onError);val ready by rememberUpdatedState(available)
    var closed by remember(model){mutableStateOf(false)};var failed by remember(model){mutableStateOf<String?>(null)}
    LaunchedEffect(model,owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {current->
                if(!ready())return@collect
                if(current.closed || current.finished){if(!closed){closed=true;close()};return@collect}
                if(!current.loaded || current.busy)return@collect
                val token=current.pending?.token ?: return@collect
                if(failed==token && current.error!=null)return@collect
                try{
                    model.claim(token){owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()}?.let {
                        try{deliver(it)}catch(failure:Exception){error(failure)}
                        if(!closed){closed=true;close()}
                    }
                }catch(cancel:CancellationException){throw cancel}
                catch(failure:Exception){currentCoroutineContext().ensureActive();failed=token;model.failure(failure);error(failure)}
            }
        }
    }
    BackHandler(enabled=!state.busy){model.close()}
    ChapterDownloadScreen(state,model::start,model::end,model::confirm,model::close,model::retry)
}

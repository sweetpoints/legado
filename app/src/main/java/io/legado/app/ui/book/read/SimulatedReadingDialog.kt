package io.legado.app.ui.book.read

import android.content.DialogInterface
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.*
import io.legado.app.model.ReadBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.setLayout
import kotlinx.coroutines.*
import java.time.LocalDate

class SimulatedReadingDialog:BaseComposeDialogFragment() {
    private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val model by viewModels<SimulatedReadingViewModel> { viewModelFactory { initializer {
        SimulatedReadingViewModel(RoomSimulatedReadingRepository(),FileSimulatedReadingRequestRepository(requireContext()),
            requireArguments().getString(TICKET)!!,createSavedStateHandle(),cleanup)
    } } }
    override fun onStart() {super.onStart();setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)}
    @Composable override fun Content() {
        val state by model.state.collectAsStateWithLifecycle();val owner=LocalLifecycleOwner.current
        SideEffect {isCancelable=!state.saving}
        LaunchedEffect(model,owner) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { if(it.finished) {
                    model.claim()?.let { applied ->
                        ReadBook.book?.takeIf { book->book.bookUrl==applied.bookUrl }?.let { book ->
                            book.setReadSimulating(applied.initial.enabled);book.setStartDate(LocalDate.parse(applied.initial.date))
                            book.setStartChapter(applied.initial.start.toInt());book.setDailyChapters(applied.initial.daily.toInt())
                            ReadBook.clearTextChapter()
                            parentFragmentManager.setFragmentResult(RESULT,Bundle().apply {putString("owner",MD5Utils.md5Encode(applied.bookUrl))})
                        }
                    }
                    dismiss()
                } }
            }
        }
        SimulatedReadingScreen(state,model::enabled,model::start,model::daily,model::date,model::dateOpen,
            model::save,model::cancel,model::retry,Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.8f).imePadding())
    }
    override fun onDismiss(dialog:DialogInterface) {
        if(activity?.isChangingConfigurations!=true) {model.cancel();model.release()}
        super.onDismiss(dialog)
    }
    companion object {
        const val RESULT="simulation-reading-result"
        private const val TICKET="simulation-reading-ticket"
        fun newInstance(ticket:String)=SimulatedReadingDialog().apply {arguments=Bundle().apply {putString(TICKET,ticket)}}
    }
}

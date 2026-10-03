package io.legado.app.ui.book.info.edit

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.*
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class BookInfoEditActivity:BaseComposeActivity(),ChangeCoverDialog.CallBack {
    val viewModel by viewModels<BookMetadataEditorViewModel>{viewModelFactory{initializer{
        val books=RoomBookMetadataEditorRepository()
        val saved=createSavedStateHandle().apply{remove<String>("bookUrl")}
        BookMetadataEditorViewModel(saved,books,FileBookMetadataEditorSessionRepository(applicationContext,books),
            FileBookMetadataCoverImportRepository(applicationContext),intent.getStringExtra("bookUrl"))
    }}}
    private var pickerToken:String?=null
    private val selectCover=registerForActivityResult(HandleFileContract()){result->
        val token=result.value ?: pickerToken
        if(token!=null)viewModel.coverResult(token,result.uri?.toString())
        if(token==pickerToken)pickerToken=null
    }
    override fun onComposeCreated(savedInstanceState:Bundle?){pickerToken=savedInstanceState?.getString("book.metadata.picker")}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("book.metadata.picker",pickerToken);super.onSaveInstanceState(outState)}
    @Composable override fun Content(savedInstanceState:Bundle?) {
        BookMetadataEditorRoute(viewModel,{ok->if(ok)setResult(RESULT_OK);viewModel.close();super.finish()}, {book,highlights->
            ReadBook.book?.takeIf{it.bookUrl==book.bookUrl}?.let{current->
                // Keep live reader progress/configuration, applying only the committed metadata.
                val updated=current.copy(name=book.name,author=book.author,type=book.type,customCoverUrl=book.customCoverUrl,
                    persistedCoverUrl=book.persistedCoverUrl,customIntro=book.customIntro)
                ReadBook.book=updated;ReadBook.applyPreparedHighlights(updated.bookUrl,highlights)
            }
        }, {navigation,book->when(navigation.action){
            BookMetadataAction.ChangeCover->showDialogFragment(ChangeCoverDialog(book.name,book.author))
            BookMetadataAction.PickCover->{pickerToken=navigation.token;selectCover.launch{mode=HandleFileContract.IMAGE;value=navigation.token}}
        }},{toastOnUi(it)},{!supportFragmentManager.isStateSaved})
    }
    override fun coverChangeTo(coverUrl:String){viewModel.receiveCover(coverUrl)}
    override fun finish(){viewModel.close();super.finish()}
}

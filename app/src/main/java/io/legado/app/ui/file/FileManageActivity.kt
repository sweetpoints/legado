package io.legado.app.ui.file

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.AppFileManagementRepository
import io.legado.app.data.repository.AtomicFileManagementDraftRepository
import io.legado.app.utils.openFileUri
import io.legado.app.utils.toastOnUi

class FileManageActivity : BaseComposeActivity() {
    val viewModel by
        viewModels<FileManagementViewModel> {
            viewModelFactory {
                initializer {
                    FileManagementViewModel(
                        AppFileManagementRepository(applicationContext),
                        AtomicFileManagementDraftRepository(applicationContext),
                        createSavedStateHandle(),
                    )
                }
            }
        }

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        FileManagementRoute(
            viewModel,
            { super.finish() },
            { openFileUri(Uri.parse(it)) },
            { toastOnUi(it) },
            { !supportFragmentManager.isStateSaved },
        )
    }

    override fun finish() {
        viewModel.close()
        super.finish()
    }
}

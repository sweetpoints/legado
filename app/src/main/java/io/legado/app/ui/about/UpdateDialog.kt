package io.legado.app.ui.about

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.*
import io.legado.app.help.update.AppUpdate
import io.legado.app.model.Download
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

class UpdateDialog() : BaseComposeDialogFragment() {
    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments =
            Bundle().apply {
                putString(
                    "requestId",
                    FileUpdateDialogRepository.stage(
                        appCtx,
                        UpdateDialogRequest(
                            updateInfo.tagName,
                            updateInfo.updateLog,
                            updateInfo.downloadUrl,
                            updateInfo.fileName,
                            updateInfo.backupDownloadUrl,
                            updateInfo.mirrorDownloadUrl,
                            updateInfo.alternateMirrorDownloadUrl,
                            updateInfo.size,
                            updateInfo.createdAt,
                            updateInfo.isBeta,
                        ),
                    ),
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = arguments
        if (legacy?.containsKey("requestId") != true) {
            arguments =
                Bundle().apply {
                    putBoolean("noData", legacy?.getString("updateBody") == null)
                    putString(
                        "requestId",
                        FileUpdateDialogRepository.stage(
                            appCtx,
                            UpdateDialogRequest(
                                legacy?.getString("newVersion").orEmpty(),
                                legacy?.getString("updateBody").orEmpty(),
                                legacy?.getString("url").orEmpty(),
                                legacy?.getString("name").orEmpty(),
                                legacy?.getString("backupUrl"),
                                legacy?.getString("mirrorUrl"),
                                legacy?.getString("alternateMirrorUrl"),
                                legacy?.getLong("size") ?: 0,
                                legacy?.getLong("createdAt") ?: 0,
                                legacy?.getBoolean("isBeta") == true,
                            ),
                        ),
                    )
                }
        }
    }

    internal val model by
        viewModels<UpdateDialogViewModel> {
            viewModelFactory {
                initializer {
                    UpdateDialogViewModel(
                        FileUpdateDialogRepository(requireContext()),
                        createSavedStateHandle(),
                        requireArguments().getString("requestId")!!,
                    )
                }
            }
        }
    private val images by lazy { GlideMarkdownImageRepository(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onComposeCreated(savedInstanceState: Bundle?) {
        if (arguments?.getBoolean("noData") == true) {
            toastOnUi("没有数据")
            model.cancel()
        }
    }

    @Composable
    override fun Content() {
        UpdateDialogRoute(
            model,
            images,
            {
                isAdded && !parentFragmentManager.isStateSaved && !childFragmentManager.isStateSaved
            },
            ::deliver,
            { requireContext().openUrl(it) },
            { showDialogFragment(PhotoDialog(it)) },
            ::dismissAllowingStateLoss,
            { isCancelable = it },
        )
    }

    private fun deliver(effect: UpdateDialogEffect) {
        when (effect.action) {
            UpdateDialogAction.Download -> {
                Download.start(
                    requireContext(),
                    requireNotNull(effect.url),
                    requireNotNull(effect.fileName),
                    isAppUpdate = true,
                )
                toastOnUi(R.string.download_start)
            }
            UpdateDialogAction.Browser -> requireContext().openUrl(requireNotNull(effect.url))
            UpdateDialogAction.IgnoredNotice -> toastOnUi(R.string.ignore_this_version)
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        model.cancel()
        super.onCancel(dialog)
    }
}

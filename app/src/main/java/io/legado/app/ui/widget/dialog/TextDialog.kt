package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.repository.*
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.openUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import splitties.init.appCtx

class TextDialog() : BaseComposeDialogFragment() {
    enum class Mode {
        MD,
        HTML,
        TEXT,
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false,
        showToc: Boolean = false,
    ) : this() {
        arguments =
            Bundle().apply {
                putString(
                    "requestId",
                    FileTextDialogRequestRepository.stage(
                        appCtx,
                        TextDialogRequest(
                            title,
                            content.orEmpty(),
                            mode.name,
                            time,
                            autoClose,
                            showToc && mode == Mode.MD,
                        ),
                    ),
                )
            }
        isCancelable = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val legacy = arguments
        if (legacy != null && !legacy.containsKey("requestId")) {
            val help = legacy.getBoolean("showToc")
            val content =
                if (help) legacy.getString("content").orEmpty()
                else IntentData.get<String>(legacy.getString("content")).orEmpty()
            arguments =
                Bundle().apply {
                    putString(
                        "requestId",
                        FileTextDialogRequestRepository.stage(
                            appCtx,
                            TextDialogRequest(
                                legacy.getString("title").orEmpty(),
                                content,
                                legacy.getString("mode") ?: Mode.TEXT.name,
                                legacy.getLong("time"),
                                legacy.getBoolean("autoClose"),
                                help,
                            ),
                        ),
                    )
                }
        }
    }

    private val model by
        viewModels<TextDialogViewModel> {
            viewModelFactory {
                initializer {
                    TextDialogViewModel(
                        FileTextDialogRequestRepository(requireContext()),
                        createSavedStateHandle(),
                        requireArguments().getString("requestId")!!,
                    )
                }
            }
        }
    private val images by lazy { GlideMarkdownImageRepository(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, .9f)
    }

    @Composable
    override fun Content() {
        TextDialogRoute(
            model,
            images,
            { isAdded && !parentFragmentManager.isStateSaved },
            { requireContext().openUrl(it) },
            { showDialogFragment(PhotoDialog(it)) },
            ::openEditor,
            ::dismissAllowingStateLoss,
            { isCancelable = it },
        )
    }

    private fun openEditor(request: TextDialogRequest) {
        val key = "code_text_${System.nanoTime()}"
        CacheManager.putMemory(key, request.content)
        startActivity<CodeEditActivity> {
            putExtra("cacheKey", key)
            putExtra("title", request.title)
            putExtra(
                "languageName",
                if (request.mode == Mode.MD.name) "text.html.markdown" else "text.html.basic",
            )
        }
    }
}

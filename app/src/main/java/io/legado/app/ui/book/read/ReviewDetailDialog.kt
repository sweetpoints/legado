package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AppReviewDetailStore
import io.legado.app.data.repository.DefaultReviewDetailRepository
import io.legado.app.data.repository.ReviewDetailKey
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.photo.GlidePhotoImageLoader
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize
import splitties.systemservices.windowManager
import kotlin.math.roundToInt

class ReviewDetailDialog() : BaseComposeDialogFragment() {
    constructor(paragraphNum: Int, totalCount: Int, chapterIndex: Int, paragraphData: String?,
        bookUrl: String, sourceKey: String, ruleHash: Int) : this() {
        arguments = Bundle().apply {
            putInt("paragraphNum", paragraphNum); putInt("totalCount", totalCount); putInt("chapterIndex", chapterIndex)
            putString("paragraphData", paragraphData); putString("bookUrl", bookUrl); putString("sourceKey", sourceKey); putInt("ruleHash", ruleHash)
        }
    }
    private val key by lazy { ReviewDetailKey(arguments?.getInt("paragraphNum") ?: 0, arguments?.getInt("chapterIndex") ?: 0,
        arguments?.getString("paragraphData").orEmpty(), arguments?.getString("bookUrl").orEmpty(),
        arguments?.getString("sourceKey").orEmpty(), arguments?.getInt("ruleHash") ?: 0) }
    private val viewModel by viewModels<ReviewDetailViewModel> {
        viewModelFactory { initializer { ReviewDetailViewModel(DefaultReviewDetailRepository(AppReviewDetailStore(requireContext())),
            createSavedStateHandle(), key, lastHeightRatio) } }
    }
    private var appliedHeight = 0
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onStart() {
        super.onStart(); appliedHeight = 0
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.color.transparent); decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply { dimAmount = .16f; gravity = Gravity.BOTTOM }
        }
        applyHeight(viewModel.state.value.heightRatio)
    }
    private fun applyHeight(ratio: Float) {
        val height = requireContext().windowManager.windowSize.heightPixels
        if (height <= 0) return
        val bounded = ratio.coerceIn(.35f, .92f); val pixels = (height * bounded).roundToInt()
        lastHeightRatio = bounded
        if (pixels != appliedHeight) { appliedHeight = pixels; setLayout(1f, pixels) }
    }
    @Composable override fun Content() {
        val loader = remember { GlidePhotoImageLoader(requireContext()) }
        val audio = remember { ReviewDetailAudio(requireContext()) { message ->
            AppLog.put("段评语音播放失败\n$message")
            toastOnUi(message.ifBlank { getString(R.string.load_over_time) })
        } }
        ReviewDetailRoute(viewModel, arguments?.getInt("totalCount") ?: 0, key.sourceKey, loader, audio,
            { isAdded && !childFragmentManager.isStateSaved }, { url ->
                if (childFragmentManager.findFragmentByTag("reviewPhoto") == null) PhotoDialog(url, key.sourceKey).show(childFragmentManager, "reviewPhoto")
            }, { message -> toastOnUi(message) }, ::applyHeight, { dismissAllowingStateLoss() }, Modifier.fillMaxSize())
    }
    override fun dismiss() { viewModel.cancel() }
    private companion object { var lastHeightRatio = .68f }
}

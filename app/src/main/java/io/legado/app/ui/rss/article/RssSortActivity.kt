package io.legado.app.ui.rss.article

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.repository.*
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity

/** Direct Compose categories and pages retain the existing public singleTop/navigation entry. */
class RssSortActivity : BaseComposeActivity(), VariableDialog.Callback {
    internal val categoryModel by viewModels<RssCategoryViewModel> {
        viewModelFactory { initializer { RssCategoryViewModel(AppRssCategoryRepository(), FileRssCategorySessionRepository(), createSavedStateHandle().withoutRssIntent()) } }
    }
    private val images by lazy { GlideRssArticleImageRepository(applicationContext) }
    private val reader by lazy { AppRssArticlesReadRepository() }
    private val editSourceResult = registerForActivityResult(StartActivityContract(RssSourceEditActivity::class.java)) {
        if (it.resultCode == RESULT_OK) categoryModel.edited()
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        // Remove only restored pre-migration page fragments; independent dialogs keep their owners.
        supportFragmentManager.fragments.filter { it.javaClass.name == "io.legado.app.ui.rss.article.RssArticlesFragment" }
            .takeIf { it.isNotEmpty() }?.let { old ->
                supportFragmentManager.beginTransaction().apply { old.forEach(::remove) }.commitNow()
            }
        categoryModel.bind(if (savedInstanceState == null) request(intent) else null)
        onBackPressedDispatcher.addCallback(this) { closeOrExitSearch() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        categoryModel.bind(request(intent), reuseSortUrl = true)
    }
    private fun request(intent: Intent) = RssCategoryRequest(intent.getStringExtra("sourceUrl"), intent.getStringExtra("sortUrl"), intent.getStringExtra("key"))
    private fun closeOrExitSearch() {
        if (categoryModel.state.value.busy) return
        if (!categoryModel.exitSearch()) finish()
    }
    private fun ready() = !isFinishing && !supportFragmentManager.isStateSaved
    @Composable override fun Content(savedInstanceState: Bundle?) {
        val state by categoryModel.state.collectAsStateWithLifecycle()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        RssCategoryRoute(categoryModel, landscape, ::ready, ::closeOrExitSearch, ::native) { index, active ->
            val tab = state.tabs.getOrNull(index)
            val origin = state.request?.sourceUrl
            if (tab != null && origin != null) {
                // Stable index owners are rebound on source/category changes, rather than leaking a VM per refresh.
                val model = remember(index) {
                    ViewModelProvider(this@RssSortActivity, viewModelFactory { initializer {
                        RssArticlesPageViewModel(AppRssArticlesPageRepository(), FileRssArticlesSessionRepository(), createSavedStateHandle().withoutRssIntent())
                    } })["rss-articles-page-$index", RssArticlesPageViewModel::class.java]
                }
                val parameters = RssArticlesParameters(origin, tab.name, tab.url, state.request?.query, state.preload, state.contentRevision)
                RssArticlesPageRoute(model, parameters, state.style, landscape, active, { active && ready() },
                    { value -> ReadRss.readRss(this, value.article, value.source) }, images, reader)
            }
        }
    }
    private fun native(value: RssCategoryNative) {
        when (value.effect.kind) {
            RssCategoryEffectKind.Login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource"); putExtra("key", value.source.sourceUrl)
            }
            RssCategoryEffectKind.EditSource -> editSourceResult.launch { putExtra("sourceUrl", value.source.sourceUrl) }
            RssCategoryEffectKind.ReadRecords -> showDialogFragment(ReadRecordDialog(value.source.sourceUrl))
            RssCategoryEffectKind.Variable -> value.variable?.let { variable ->
                showDialogFragment(VariableDialog(getString(R.string.set_source_variable), variable.key, variable.value, variable.comment))
            }
        }
    }
    override fun setVariable(key: String, variable: String?) { categoryModel.setVariable(key, variable) }
    companion object {
        fun start(context: Context, sortUrl: String?, sourceUrl: String, key: String? = null) {
            context.startActivity<RssSortActivity> {
                putExtra("sortUrl", sortUrl); putExtra("sourceUrl", sourceUrl); putExtra("key", key)
            }
        }
    }
}

/** Activity default arguments also enter SavedStateHandle; large inputs belong only to owned disk sessions. */
private fun SavedStateHandle.withoutRssIntent() = apply {
    remove<String>("sourceUrl"); remove<String>("sortUrl"); remove<String>("key")
}

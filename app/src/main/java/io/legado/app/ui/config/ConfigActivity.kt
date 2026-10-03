package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.repository.FileConfigSearchSessionRepository
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.main.my.MyMoreActivity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/** Compose chrome and private search state host the existing Compose settings destinations. */
class ConfigActivity : BaseComposeActivity() {
    val viewModel by viewModels<ConfigViewModel>()
    internal val searchModel by viewModels<ConfigSearchViewModel> { viewModelFactory { initializer {
        ConfigSearchViewModel(FileConfigSearchSessionRepository(applicationContext), createSavedStateHandle().apply {
            keys().filterNot { it.startsWith("config.search.") }.forEach { remove<Any?>(it) }
        })
    } } }
    private var pageTitle by mutableStateOf("")
    private var pageReady by mutableStateOf(false)

    override fun shouldCreateContentView(): Boolean {
        if (intent.getStringExtra("configTag") == ConfigTag.MY_MORE) {
            startActivity<MyMoreActivity>(); finish(); return false
        }
        return true
    }
    override fun onComposeCreated(savedInstanceState: Bundle?) {
        pageTitle = getString(R.string.setting)
        if (pageClass(intent.getStringExtra("configTag")) == null) finish()
    }
    override fun setTitle(resId: Int) { super.setTitle(resId); pageTitle = getString(resId) }
    private fun pageClass(tag: String?): Class<out Fragment>? = when (tag) {
        ConfigTag.OTHER_CONFIG -> OtherConfigFragment::class.java
        ConfigTag.THEME_CONFIG -> ThemeConfigFragment::class.java
        ConfigTag.BACKUP_CONFIG -> BackupConfigFragment::class.java
        ConfigTag.COVER_CONFIG -> CoverConfigFragment::class.java
        ConfigTag.COVER_FONT_CONFIG -> CoverFontConfigFragment::class.java
        ConfigTag.WELCOME_CONFIG -> WelcomeConfigFragment::class.java
        else -> null
    }
    private fun attachPage() {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        val tag = intent.getStringExtra("configTag") ?: return
        val type = pageClass(tag) ?: return
        if (supportFragmentManager.findFragmentByTag(tag) == null) {
            val page = supportFragmentManager.fragmentFactory.instantiate(classLoader, type.name)
            supportFragmentManager.beginTransaction().replace(R.id.configFrameLayout, page, tag).commitNow()
        }
        pageReady = supportFragmentManager.findFragmentByTag(tag) is ConfigSearchPage
    }
    @Composable override fun Content(savedInstanceState: Bundle?) {
        val state by searchModel.state.collectAsStateWithLifecycle()
        val draft = state.draft
        LaunchedEffect(searchModel, lifecycle, pageReady) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                searchModel.state.collect { current ->
                    val pending = current.draft.request ?: return@collect
                    if (!pageReady || isFinishing || supportFragmentManager.isStateSaved) return@collect
                    val page = supportFragmentManager.findFragmentById(R.id.configFrameLayout) as? ConfigSearchPage ?: return@collect
                    try {
                        searchModel.claim(pending.token) { pageReady && !isFinishing && !supportFragmentManager.isStateSaved && lifecycle.currentState == Lifecycle.State.RESUMED }
                            ?.let { request -> page.searchSettings(request.query) { searchModel.selected(request.token) } }
                    } catch (canceled: CancellationException) { throw canceled }
                    catch (error: Exception) { AppLog.put("设置搜索交付失败", error) }
                }
            }
        }
        ConfigScaffold(pageTitle, draft.searching, TextFieldValue(draft.text, TextRange(draft.start, draft.end)),
            { searchModel.text(it.text, it.selection.start, it.selection.end) }, searchModel::searching,
            { searchModel.submit() }, ::finish, enabled = state.editable) {
            Column(Modifier.fillMaxSize()) {
                state.error?.let { message -> Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(searchModel::retry, enabled = !state.saving, modifier = Modifier.testTag("config-search-retry")) { Text(getString(R.string.retry)) }
                } }
                AndroidView(factory = { context -> FragmentContainerView(context).apply {
                    id = R.id.configFrameLayout
                    doOnAttach { attachPage() }
                } }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    override fun observeLiveBus() { super.observeLiveBus(); observeEvent<String>(EventBus.RECREATE) { recreate() } }
    override fun onStop() {
        val captured = searchModel
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }.onError { AppLog.put("保存设置搜索草稿失败", it) }
        super.onStop()
    }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            val captured = searchModel; captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }.onError { AppLog.put("清理设置搜索草稿失败", it) }
        }
        super.onDestroy()
    }
}

internal fun configPreferenceMatches(
    query: String,
    title: CharSequence?,
    summary: CharSequence?,
    categories: List<CharSequence>,
): Boolean {
    if (query.isBlank()) return false
    return title?.contains(query, ignoreCase = true) == true ||
            summary?.contains(query, ignoreCase = true) == true ||
            categories.any { it.contains(query, ignoreCase = true) }
}

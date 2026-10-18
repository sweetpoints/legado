package io.legado.app.ui.code

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.legado.app.base.BaseThemedActivity
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.config.ChangeThemeDialog
import io.legado.app.ui.code.config.SettingsDialog
import io.legado.app.ui.widget.keyboard.KeyboardAssistsConfig
import io.legado.app.utils.observeEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Platform launch values, public result contract and existing Compose dialog hosts only. */
class CodeEditActivity :
    BaseThemedActivity(),
    ChangeThemeDialog.CallBack,
    SettingsDialog.CallBack,
    CurlAnalyzeUrlDialog.Callback {
    companion object {
        const val EXTRA_SHOW_DEBUG_SOURCE = "showDebugSourceAction"
        const val EXTRA_SHOW_LOGIN_SOURCE = "showLoginSourceAction"
        const val EXTRA_CHECK_JAVASCRIPT_SYNTAX = "checkJavaScriptSyntax"
        const val EXTRA_RESULT_ACTION = "resultAction"
        const val RESULT_ACTION_DEBUG_SOURCE = "debugSource"
        const val RESULT_ACTION_LOGIN_SOURCE = "loginSource"
    }

    private val controller = CodeEditorController()
    private var curlOwner: String? = null
    private val model by
        viewModels<CodeEditorComposeViewModel> {
            viewModelFactory {
                initializer {
                    val launch = legacyLaunch()
                    val savedState = createSavedStateHandle()
                    clearCodeEditorLaunchDefaults(savedState)
                    CodeEditorComposeViewModel(
                        FileCodeEditorSessionRepository(applicationContext),
                        savedState,
                        launch,
                    )
                }
            }
        }

    private fun legacyLaunch() =
        CodeEditorLaunch(
            cacheKey = intent.getStringExtra("cacheKey"),
            textFile = intent.getStringExtra("textFile"),
            text = intent.getStringExtra("text"),
            readOnly = intent.getBooleanExtra("readOnly", false),
            cursorPosition = intent.getIntExtra("cursorPosition", 0),
            title = intent.getStringExtra("title"),
            languageName = intent.getStringExtra("languageName"),
            checkJavaScriptSyntax = intent.getBooleanExtra(EXTRA_CHECK_JAVASCRIPT_SYNTAX, false),
            showDebugSource = intent.getBooleanExtra(EXTRA_SHOW_DEBUG_SOURCE, false),
            showLoginSource = intent.getBooleanExtra(EXTRA_SHOW_LOGIN_SOURCE, false),
            returnUnchangedText = intent.getBooleanExtra("returnUnchangedText", false),
            useTextFile = intent.getBooleanExtra("useTextFile", false),
        )

    internal val nativeCoordinator by lazy {
        CodeEditorNativeCoordinator(model, controller, lifecycle, lifecycleScope,
            ::platformAction, ::returnResult, ::closeHost, { !isFinishing && !isDestroyed })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = nativeCoordinator.exit()
        })
        super.onCreate(savedInstanceState)
    }

    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = io.legado.app.R.id.code_editor_flutter_container })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        nativeCoordinator
        if (editorFragment == null) supportFragmentManager.beginTransaction()
            .add(io.legado.app.R.id.code_editor_flutter_container, CodeEditorFlutterFragment(), "code-editor-flutter").commitNow()
        model.keyboardRows(AppConfig.showBoardLine)
        observeEvent<Int>(PreferKey.showBoardLine) { model.keyboardRows(it) }
        lifecycleScope.launch {
            model.state
                .map { it.session != null }
                .distinctUntilChanged()
                .collect { accepted ->
                    // Clear the carrier only after private bootstrap was accepted. Failed IO still
                    // owns its immutable seed; a restored session uses its UUID, never these
                    // extras.
                    if (accepted) clearCodeEditorLaunchIntent(intent)
                }
        }
    }

    private val editorFragment get() = supportFragmentManager.findFragmentByTag("code-editor-flutter") as? CodeEditorFlutterFragment
    override fun onPostResume() { super.onPostResume(); editorFragment?.onPostResume() }
    override fun onNewIntent(intent: Intent) { editorFragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        editorFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); editorFragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); editorFragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); editorFragment?.onTrimMemory(level) }
    override fun onDestroy() { nativeCoordinator.close(); super.onDestroy() }

    private fun returnResult(payload: CodeEditorResultPayload) {
        val result =
            Intent().apply {
                putExtra("cursorPosition", payload.cursorPosition)
                payload.text?.let { putExtra("text", it) }
                payload.textFile?.let { putExtra("textFile", it) }
                payload.action?.let { putExtra(EXTRA_RESULT_ACTION, it) }
            }
        if (payload.text != null || payload.textFile != null || payload.cursorPosition > 0) {
            setResult(RESULT_OK, result)
        }
        super.finish()
    }

    private fun closeHost() = super.finish()

    override fun finish() {
        controller.exit?.invoke() ?: super.finish()
    }

    private fun platformAction(action: CodeEditorAction) {
        when (action) {
            CodeEditorAction.THEME -> showDialogFragment(ChangeThemeDialog())
            CodeEditorAction.SETTINGS -> showDialogFragment(SettingsDialog(this, this))
            CodeEditorAction.CURL -> {
                curlOwner = controller.owner
                showDialogFragment(
                    CurlAnalyzeUrlDialog(
                        (controller.engine as? SoraCodeEditorEngine)?.selectedText().orEmpty(),
                        model.state.value.session?.writable == true,
                    )
                )
            }
            CodeEditorAction.WRAP -> {
                val enabled = !AppConfig.editAutoWrap
                upEdit(autoWarp = enabled)
                putPrefBoolean(PreferKey.editAutoWrap, enabled)
            }
            CodeEditorAction.LOG -> showDialogFragment<AppLogDialog>()
            CodeEditorAction.KEYBOARD_CONFIG -> showDialogFragment<KeyboardAssistsConfig>()
            CodeEditorAction.RULE_HELP -> showHelp("ruleHelp")
            CodeEditorAction.RSS_HELP -> showHelp("rssRuleHelp")
            CodeEditorAction.JS_HELP -> showHelp("jsHelp")
            CodeEditorAction.REGEX_HELP -> showHelp("regexHelp")
            CodeEditorAction.HELP -> showHelp("ruleHelp")
            else -> Unit
        }
    }

    override fun upEdit(
        fontSize: Int?,
        autoComplete: Boolean?,
        autoWarp: Boolean?,
        editNonPrintable: Int?,
    ) {
        (controller.engine as? SoraCodeEditorEngine)?.settings(
            fontSize,
            autoComplete,
            autoWarp,
            editNonPrintable,
        )
    }

    override fun upTheme(index: Int) {
        (controller.engine as? SoraCodeEditorEngine)?.theme(index)
    }

    override fun onCurlAnalyzeUrlInsert(text: String, onResult: (Boolean) -> Unit) {
        val engine = controller.engine
        if (
            model.state.value.session?.writable != true ||
                engine == null ||
                model.state.value.busy ||
                curlOwner == null ||
                curlOwner != controller.owner ||
                curlOwner != model.state.value.engineOwner
        ) {
            onResult(false)
        } else engine.insert(text, onResult)
    }
}

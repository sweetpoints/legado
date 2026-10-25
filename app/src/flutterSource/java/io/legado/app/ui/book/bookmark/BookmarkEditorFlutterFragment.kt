package io.legado.app.ui.book.bookmark

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.util.GeneratedPluginRegister

/**
 * UI engine only: source execution continues in the native ViewModel and its separate V8 engine.
 */
class BookmarkEditorFlutterFragment : FlutterFragment() {
    private var editorBridge: BookmarkEditorChannelBridge? = null

    override fun getRenderMode(): io.flutter.embedding.android.RenderMode = io.flutter.embedding.android.RenderMode.texture
    override fun getTransparencyMode(): io.flutter.embedding.android.TransparencyMode = io.flutter.embedding.android.TransparencyMode.transparent

    override fun onAttach(context: Context) {
        requireArguments().putBoolean(ARG_SHOULD_AUTOMATICALLY_HANDLE_ON_BACK_PRESSED, true)
        super.onAttach(context)
    }

    // Do not accept external route/entrypoint/engine carriers during Fragment restoration.
    override fun getDartEntrypointFunctionName(): String = "embeddedUiMain"

    override fun getDartEntrypointLibraryUri(): String? = null

    override fun getDartEntrypointArgs(): List<String> = emptyList()

    override fun getInitialRoute(): String = "/"

    override fun shouldHandleDeeplinking(): Boolean = false

    override fun getAppBundlePath(): String =
        FlutterInjector.instance().flutterLoader().findAppBundlePath()

    override fun getCachedEngineId(): String? = null

    override fun getCachedEngineGroupId(): String? = null

    override fun provideFlutterEngine(context: Context): FlutterEngine? = null

    override fun shouldDestroyEngineWithHost(): Boolean = true

    override fun shouldAttachEngineToActivity(): Boolean = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        GeneratedPluginRegister.registerGeneratedPlugins(flutterEngine)
        editorBridge =
            BookmarkEditorChannelBridge(
                requireContext(),
                flutterEngine.dartExecutor.binaryMessenger,
                (requireParentFragment() as BookmarkDialog).coordinator,
            )
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        try {
            editorBridge?.close()
            editorBridge = null
        } finally {
            super.cleanUpFlutterEngine(flutterEngine)
        }
    }

    override fun popSystemNavigator(): Boolean {
        val dialog = requireParentFragment() as BookmarkDialog
        if (!dialog.model.state.value.busy) dialog.coordinator.requestClose()
        return true
    }
}

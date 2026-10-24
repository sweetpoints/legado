package io.legado.app.ui.book.toc.rule

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.util.GeneratedPluginRegister

/**
 * UI engine only: source execution continues in the native ViewModel and its separate V8 engine.
 */
class TxtTocManagerFlutterFragment : FlutterFragment() {
    private var managerBridge: TxtTocManagerChannelBridge? = null

    override fun getRenderMode(): io.flutter.embedding.android.RenderMode = io.flutter.embedding.android.RenderMode.texture
    override fun getTransparencyMode(): io.flutter.embedding.android.TransparencyMode = io.flutter.embedding.android.TransparencyMode.transparent

    override fun onAttach(context: Context) {
        requireArguments().putBoolean(ARG_SHOULD_AUTOMATICALLY_HANDLE_ON_BACK_PRESSED, false)
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
        val picker = parentFragment as? TxtTocRuleDialog
        val activity = if (picker == null) requireActivity() as TxtTocRuleActivity else null
        managerBridge = TxtTocManagerChannelBridge(
            requireContext(), flutterEngine.dartExecutor.binaryMessenger,
            picker?.viewModel ?: checkNotNull(activity).viewModel,
            { if (picker != null) picker.closeManagement() else checkNotNull(activity).closeManagement() },
            picker != null,
            { op, id -> if (picker != null) picker.nativeAction(op, id) else checkNotNull(activity).nativeAction(op, id) },
            { if (picker != null) picker.isResumed else activity?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true },
        )
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        try {
            managerBridge?.close()
            managerBridge = null
        } finally {
            super.cleanUpFlutterEngine(flutterEngine)
        }
    }

    override fun popSystemNavigator(): Boolean {
        val picker = parentFragment as? TxtTocRuleDialog
        if (picker != null) picker.closeManagement() else (requireActivity() as TxtTocRuleActivity).closeManagement()
        return true
    }
}

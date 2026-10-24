package io.legado.app.ui.highlight

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.util.GeneratedPluginRegister

/** Restorable UI-only engine. Never borrows the application's headless source engine. */
class HighlightManagerFlutterFragment : FlutterFragment() {
    private var managerBridge: HighlightPresentationChannelBridge? = null

    override fun onAttach(context: Context) {
        // The no-argument constructor is used during FragmentManager restoration.
        // The Activity owns one callback and forwards Back through the public SDK API.
        requireArguments().putBoolean(ARG_SHOULD_AUTOMATICALLY_HANDLE_ON_BACK_PRESSED, false)
        super.onAttach(context)
    }

    // Ignore external arguments, cached engines and Intent/deep-link routes.
    override fun getDartEntrypointFunctionName(): String = "embeddedUiMain"
    override fun getDartEntrypointLibraryUri(): String? = null
    override fun getDartEntrypointArgs(): List<String> = emptyList()
    override fun getInitialRoute(): String = "/"
    override fun shouldHandleDeeplinking(): Boolean = false
    override fun getAppBundlePath(): String = FlutterInjector.instance().flutterLoader().findAppBundlePath()
    override fun getCachedEngineId(): String? = null
    override fun getCachedEngineGroupId(): String? = null
    override fun provideFlutterEngine(context: Context): FlutterEngine? = null
    override fun shouldDestroyEngineWithHost(): Boolean = true
    override fun shouldAttachEngineToActivity(): Boolean = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Unlike FlutterActivity, FlutterFragment does not register plugins itself.
        GeneratedPluginRegister.registerGeneratedPlugins(flutterEngine)
        managerBridge = HighlightPresentationChannelBridge(
            requireContext(),
            flutterEngine.dartExecutor.binaryMessenger,
            (requireActivity() as HighlightRuleActivity),
            null,
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

    // Only a cold bootstrap/root navigator pop reaches this fallback. Loaded routes
    // consume Back in PopScope and send the typed modal-cancel/back command.
    override fun popSystemNavigator(): Boolean {
        (requireActivity() as HighlightRuleActivity).viewModel.close()
        return true
    }
}

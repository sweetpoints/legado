package io.legado.app.ui.rss.source.manage

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.util.GeneratedPluginRegister

/** Restorable UI-only engine. Never borrows the application's headless source engine. */
class RssManagerFlutterFragment : FlutterFragment() {
    private var managerBridge: RssManagerChannelBridge? = null

    override fun onAttach(context: Context) {
        // The no-argument constructor is used during FragmentManager restoration.
        // Enable the SDK's callback before super attaches its back dispatcher.
        requireArguments().putBoolean(ARG_SHOULD_AUTOMATICALLY_HANDLE_ON_BACK_PRESSED, true)
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
        managerBridge = RssManagerChannelBridge(
            requireContext(),
            flutterEngine.dartExecutor.binaryMessenger,
            (requireActivity() as RssSourceActivity).managementModel,
            { (requireActivity() as RssSourceActivity).close() },
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

    // The SDK sends Flutter dialog/route pops to Dart first. A root pop becomes
    // the same durable native effect used by the toolbar and channel navigation.
    override fun popSystemNavigator(): Boolean {
        (requireActivity() as RssSourceActivity).close()
        return true
    }
}

package io.legado.app.ui.source.debug

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import io.legado.app.R
import io.legado.app.base.BaseThemedActivity

/** Restorable Flutter chrome; existing Activity names, launch extras and result owners stay intact. */
abstract class SourceDebuggerActivity : BaseThemedActivity() {
    abstract val debuggerCoordinator: SourceDebuggerNativeCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        // Register before Fragment restoration so Flutter handles overlay Back before root exit.
        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = debuggerCoordinator.requestClose()
        })
        super.onCreate(savedInstanceState)
    }

    override fun createContentView() {
        setContentView(FrameLayout(this).apply { id = R.id.source_debugger_flutter_container })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        debuggerCoordinator
        if (debuggerFragment == null) supportFragmentManager.beginTransaction()
            .add(R.id.source_debugger_flutter_container, SourceDebuggerFlutterFragment(), FRAGMENT_TAG)
            .commitNow()
    }

    protected fun canHandleDebuggerEffect(): Boolean =
        !isFinishing && !isDestroyed && !supportFragmentManager.isStateSaved

    protected fun completeDebugger() = super.finish()

    override fun finish() {
        debuggerCoordinator.requestClose()
        super.finish()
    }

    private val debuggerFragment get() = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? SourceDebuggerFlutterFragment
    override fun onPostResume() {
        super.onPostResume()
        debuggerFragment?.onPostResume()
        debuggerCoordinator.onHostReady()
    }
    override fun onNewIntent(intent: Intent) { debuggerFragment?.onNewIntent(intent); super.onNewIntent(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        debuggerFragment?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        debuggerFragment?.onActivityResult(requestCode, resultCode, data)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); debuggerFragment?.onUserLeaveHint() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); debuggerFragment?.onTrimMemory(level) }
    override fun onDestroy() { debuggerCoordinator.close(); super.onDestroy() }

    private companion object { const val FRAGMENT_TAG = "source-debugger-flutter" }
}

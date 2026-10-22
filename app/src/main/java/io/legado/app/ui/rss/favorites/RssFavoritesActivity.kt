package io.legado.app.ui.rss.favorites

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import io.legado.app.R
import io.legado.app.base.BaseThemedActivity

/** Native container only bridges the complete ReadRss Fragment navigation contract. */
class RssFavoritesActivity : BaseThemedActivity() {
    override fun createContentView() {
        setContentView(
            FragmentContainerView(this).apply {
                id = R.id.rss_favorites_compose_container
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        )
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val manager = supportFragmentManager
        val home = manager.findFragmentByTag(HOME)
        val legacy =
            manager.fragments.filterIsInstance<RssFavoritesFragment>().filter { it !== home }
        if (home == null || legacy.isNotEmpty())
            manager
                .beginTransaction()
                .apply {
                    legacy.forEach { remove(it) }
                    if (home == null)
                        add(R.id.rss_favorites_compose_container, RssFavoritesFragment(), HOME)
                }
                .commitNow()
    }

    private val flutterView
        get() =
            (supportFragmentManager.findFragmentByTag(HOME) as? RssFavoritesFragment)?.flutterView

    override fun onPostResume() {
        super.onPostResume()
        flutterView?.onPostResume()
    }

    override fun onNewIntent(intent: Intent) {
        flutterView?.onNewIntent(intent)
        super.onNewIntent(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        flutterView?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        flutterView?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        flutterView?.onUserLeaveHint()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        flutterView?.onTrimMemory(level)
    }

    companion object {
        internal const val HOME = "rss-favorites-compose-home"
    }
}

package io.legado.app.ui.rss.favorites

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

    companion object {
        internal const val HOME = "rss-favorites-compose-home"
    }
}

package io.legado.app.ui.main.interop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewpager.widget.ViewPager

/** Temporary boundary until each main destination has a Compose Route. */
@Composable
fun LegacyMainPager(pager: ViewPager, selectedIndex: Int) {
    AndroidView(
        factory = { pager },
        modifier = Modifier.fillMaxSize(),
        update = { if (it.currentItem != selectedIndex) it.setCurrentItem(selectedIndex, false) },
    )
}

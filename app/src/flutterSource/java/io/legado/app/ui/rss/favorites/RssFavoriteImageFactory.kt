package io.legado.app.ui.rss.favorites

import android.content.Context
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader

/** Existing native loader keeps source headers, private cache and animated images on Android. */
internal class RssFavoriteImageFactory(private val host: RssFavoritesFragment) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val id = (args as? Map<*, *>)?.get("id") as? String ?: error("Invalid favorite image")
        val row = host.coordinator.image(id) ?: error("Unknown favorite image")
        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        row.image
            ?.takeIf { it.isNotBlank() }
            ?.let { source ->
                ImageLoader.load(host.requireContext(), source)
                    .apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, row.origin))
                    .centerCrop()
                    .into(image)
            }
        return object : PlatformView {
            override fun getView(): View = image

            override fun dispose() {
                Glide.with(host).clear(image)
            }
        }
    }
}

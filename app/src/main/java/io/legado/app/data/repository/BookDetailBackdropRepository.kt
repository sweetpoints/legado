package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.FutureTarget
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.image.CoverImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.BookCover
import kotlinx.coroutines.*

interface BookDetailBackdropRepository {suspend fun load(request:CoverRequest,width:Int,height:Int):CoverImage?}
/** Preserves the original BookCover blur/source URL pipeline and shares the existing animation ownership model. */
class GlideBookDetailBackdropRepository(context:Context,
    private val enabled:()->Boolean={!AppConfig.isEInkMode}):BookDetailBackdropRepository {
    private val context=context.applicationContext
    override suspend fun load(request:CoverRequest,width:Int,height:Int):CoverImage? {
        require(width>0 && height>0)
        if(!withContext(Dispatchers.IO){enabled()})return null
        var target:FutureTarget<Drawable>?=null;var animation:AnimatedDrawableResource?=null;var owned:Bitmap?=null;var delivered=false
        try {
            val result=withContext(Dispatchers.IO) {
                withContext(Dispatchers.Main.immediate) {
                    target=BookCover.loadBlur(context,request.path,request.loadOnlyWifi,request.sourceOrigin)
                        .error(ImageLoader.load(context,BookCover.defaultDrawable).transform(BlurTransformation(25),CenterCrop()))
                        .submit(width,height)
                }
                val actual=checkNotNull(target);val drawable=runInterruptible{actual.get()};ensureActive()
                if(drawable is Animatable) {
                    val resource=AnimatedDrawableResource(drawable){Glide.with(context).clear(actual)}
                    animation=resource;CoverImage.Animated(resource)
                }else {
                    val bitmap=if(drawable is BitmapDrawable)checkNotNull(drawable.bitmap.copy(Bitmap.Config.ARGB_8888,false))else
                        Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{drawable.setBounds(0,0,width,height);drawable.draw(Canvas(it))}
                    owned=bitmap;CoverImage.Static(bitmap)
                }
            }
            currentCoroutineContext().ensureActive();delivered=true;return result
        }finally {
            if(animation==null || !delivered)animation?.release() ?: withContext(NonCancellable+Dispatchers.Main.immediate){target?.let{Glide.with(context).clear(it)}}
            if(!delivered)owned?.recycle()
        }
    }
}

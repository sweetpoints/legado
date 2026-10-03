package io.legado.app.data.repository

import android.content.Context
import android.util.Base64
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestOptions
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.*
import java.io.File

/** Raw owned bytes retain animated GIFs; no globally recyclable image frame escapes the Glide lease. */
class BookDetailIntroImageRepository(context:Context) {
    private val context=context.applicationContext
    suspend fun image(source:String,sourceOrigin:String?):DictionaryImageData {
        var target:FutureTarget<File>?=null
        try{return withContext(Dispatchers.IO) {
            val bytes=if(source.startsWith("data:")) {
                val matcher=AnalyzeUrl.paramPattern.matcher(source)
                val body=if(matcher.find())source.substring(0,matcher.start())else source
                Base64.decode(body.substringAfter(','),Base64.DEFAULT)
            }else {
                withContext(Dispatchers.Main.immediate) {
                    var request=ImageLoader.loadFile(context,source)
                    if(sourceOrigin!=null)request=request.apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption,sourceOrigin))
                    target=request.submit()
                }
                runInterruptible{checkNotNull(target).get().readBytes()}
            }
            currentCoroutineContext().ensureActive();DictionaryImageData(bytes,dictionaryImageMime(bytes))
        }}finally{withContext(NonCancellable+Dispatchers.Main.immediate){target?.let{Glide.with(context).clear(it)}}}
    }
}

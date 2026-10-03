package io.legado.app.ui.book.info.detail

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.load.resource.gif.GifDrawable
import io.legado.app.data.entities.Book
import io.legado.app.data.image.CoverImage
import io.legado.app.data.repository.*
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class BookDetailBackdropTest {
    @get:Rule val compose=createComposeRule()
    private fun fixture():File {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val file=File.createTempFile("detail-backdrop-",".gif",instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("photo-animated.gif").use{input->file.outputStream().use{input.copyTo(it)}}
        return file
    }
    private fun pixel()=compose.onNodeWithTag("backdrop").captureToImage().toPixelMap().let{it[it.width/2,it.height/2]}
    @Test fun originalBlurLoaderGifChangesRealFramesStopsResumesAndReleasesBeforeGlideClear() {
        Assume.assumeFalse(AppConfig.useDefaultCover)
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val file=fixture()
        val image=runBlocking{GlideBookDetailBackdropRepository(context){true}.load(CoverRequest(file.absolutePath),90,120)} as CoverImage.Animated
        val resource=image.resource;val gif=resource.drawable as GifDrawable;val shown=mutableStateOf(true)
        val owner=object:LifecycleOwner{val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
        val repo=object:BookDetailBackdropRepository{override suspend fun load(request:CoverRequest,width:Int,height:Int)=image}
        try {
            compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED}
            val book=BookDetailBook.from(Book(bookUrl="book",customCoverUrl=file.absolutePath))
            compose.setContent{CompositionLocalProvider(LocalLifecycleOwner provides owner){if(shown.value)BookDetailBackdrop(book,Modifier.size(90.dp,120.dp).testTag("backdrop"),repo)}}
            compose.waitUntil{gif.isRunning};val first=pixel();compose.waitUntil(timeoutMillis=5000){pixel()!=first}
            compose.runOnIdle{owner.registry.currentState=Lifecycle.State.CREATED}
            compose.runOnIdle{assertFalse(gif.isRunning);assertNull(gif.callback)}
            val paused=pixel();compose.mainClock.advanceTimeBy(1000);assertEquals(paused,pixel())
            compose.runOnIdle{owner.registry.currentState=Lifecycle.State.RESUMED}
            compose.waitUntil{gif.isRunning};val resumed=pixel();compose.waitUntil(timeoutMillis=5000){pixel()!=resumed}
            compose.runOnIdle{shown.value=false};compose.waitUntil{resource.isReleased}
            compose.runOnIdle{assertFalse(gif.isRunning);assertNull(gif.callback)}
        }finally{runBlocking{resource.release()};file.delete()}
    }
    @Test fun blurredStaticBitmapIsAnOwnedCopyReadableAfterTheGlideTargetWasCleared()=runBlocking {
        Assume.assumeFalse(AppConfig.useDefaultCover)
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val file=File.createTempFile("detail-backdrop-static-",".png",context.cacheDir)
        val input=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply{eraseColor(AndroidColor.GREEN)}
        try {
            file.outputStream().use{input.compress(Bitmap.CompressFormat.PNG,100,it)}
            val image=GlideBookDetailBackdropRepository(context){true}.load(CoverRequest(file.absolutePath),64,64) as CoverImage.Static
            assertFalse(image.bitmap.isRecycled);assertEquals(AndroidColor.GREEN,image.bitmap.getPixel(32,32))
            assertNotSame(input,image.bitmap)
        }finally{input.recycle();file.delete()}
    }
    @Test fun eInkDisabledBackdropDoesNotLoadOrCreateAnImageResource()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(GlideBookDetailBackdropRepository(context){false}.load(CoverRequest("invalid-path"),64,64))
    }
}

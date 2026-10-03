package io.legado.app.ui.book.info.detail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.DictionaryImageData
import org.junit.*
import org.junit.Assert.*

class BookDetailIntroTest {
    @get:Rule val compose=createComposeRule()
    private fun image(source:String):DictionaryImageData=error("No image expected in this fixture: $source")
    private fun native(context:Context):BookDetailIntroNativeView? {
        var current=context
        while(current !is Activity && current is ContextWrapper)current=current.baseContext
        fun find(view:View):BookDetailIntroNativeView? {
            if(view is BookDetailIntroNativeView)return view
            if(view is ViewGroup)for(index in 0 until view.childCount)find(view.getChildAt(index))?.let{return it}
            return null
        }
        return (current as? Activity)?.window?.decorView?.let(::find)
    }
    @Test fun plainTextKeepsParagraphIndentAndFourLineCollapseWithSavedExpansion() {
        val tester=StateRestorationTester(compose);val raw=(1..12).joinToString("\n"){"Paragraph $it long enough to wrap to a second line."}
        val document=bookDetailIntroDocument(raw)
        compose.setContent{MaterialTheme{var expanded by rememberSaveable{mutableStateOf(true)};Column(Modifier.size(280.dp,400.dp).verticalScroll(rememberScrollState())) {
            BookDetailIntro(document,"book",null,expanded,{expanded=it},{image(it)},{},{},{})
        }}}
        val full=compose.onNodeWithTag("book-detail-intro-text").fetchSemanticsNode().boundsInRoot.height
        val text=compose.onNodeWithTag("book-detail-intro-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertEquals(raw,text.text);assertEquals(12,text.paragraphStyles.size)
        compose.onNodeWithTag("book-detail-intro-toggle").performScrollTo().performClick();val collapsed=compose.onNodeWithTag("book-detail-intro-text").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsed<full);tester.emulateSavedInstanceStateRestore()
        assertEquals(collapsed,compose.onNodeWithTag("book-detail-intro-text").fetchSemanticsNode().boundsInRoot.height,.5f)
        compose.onNodeWithTag("book-detail-intro-toggle").performScrollTo().performClick();assertTrue(compose.onNodeWithTag("book-detail-intro-text").fetchSemanticsNode().boundsInRoot.height>collapsed)
    }
    @Test fun htmlButtonTouchUsesDocumentAllowlistWithJavascriptDisabledAndUnknownActionDoesNothing() {
        val document=bookDetailIntroDocument("<usehtml><button>Go@onclick:book.name</button></usehtml>")
        var context:Context?=null;var density=1f;val scripts=mutableListOf<String>()
        compose.setContent{context=LocalContext.current;density=LocalDensity.current.density;MaterialTheme {
            BookDetailIntro(document,"book",null,true,{}, {image(it)},{scripts+=it.script},{},{})
        }}
        compose.waitUntil(timeoutMillis=15_000){compose.runOnIdle{context?.let(::native)?.let{it.isPageReady && it.progress==100 && it.contentHeight>0 && it.height>0}==true}}
        compose.onNodeWithTag("book-detail-intro-web").performTouchInput{click(Offset(20*density,18*density))}
        compose.waitUntil(timeoutMillis=15_000){scripts.size==1};assertEquals(listOf("book.name"),scripts)
        compose.runOnIdle{val view=native(context!!)!!;assertFalse(view.settings.javaScriptEnabled);assertTrue(view.navigate("https://dictionary-action.invalid/unknown"))}
        assertEquals(1,scripts.size)
    }
    @Test fun initiallyPausedWebRendererSynchronizesResumePauseAndIsDestroyedWhenRemoved() {
        val owner=Owner();owner.registry.currentState=Lifecycle.State.STARTED
        var shown by mutableStateOf(true);var context:Context?=null;var captured:BookDetailIntroNativeView?=null
        val document=bookDetailIntroDocument("<useweb><p>Web content</p></useweb>")
        compose.setContent{context=LocalContext.current;MaterialTheme{CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            if(shown)BookDetailIntro(document,"https://book.invalid/details",null,true,{}, {image(it)},{},{},{})else Text("closed")
        }}}
        compose.runOnIdle{captured=native(context!!);assertNotNull(captured);assertFalse(captured!!.isResumed);assertTrue(captured!!.settings.javaScriptEnabled);owner.registry.currentState=Lifecycle.State.RESUMED}
        compose.runOnIdle{assertTrue(captured!!.isResumed);owner.registry.currentState=Lifecycle.State.STARTED}
        compose.runOnIdle{assertFalse(captured!!.isResumed);shown=false}
        compose.runOnIdle{assertTrue(captured!!.isReleased)}
    }
    @Test fun richHtmlFourLineCollapseAndExpansionRetainCopyableContentWithoutDefaultScriptExecution() {
        var expanded by mutableStateOf(false);val document=bookDetailIntroDocument("<usehtml>"+(1..20).joinToString("<br>"){"Text line $it"}+"</usehtml>")
        compose.setContent{MaterialTheme{BookDetailIntro(document,"book",null,expanded,{expanded=it},{image(it)},{},{},{})}}
        compose.waitUntil(timeoutMillis=15_000){compose.onAllNodesWithTag("book-detail-intro-toggle").fetchSemanticsNodes().isNotEmpty()}
        val collapsed=compose.onNodeWithTag("book-detail-intro-web").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("book-detail-intro-toggle").performClick()
        compose.waitUntil(timeoutMillis=15_000){compose.onNodeWithTag("book-detail-intro-web").fetchSemanticsNode().boundsInRoot.height>collapsed+10}
        assertTrue(expanded);assertTrue(document.rich!!.text.contains("Text line 20"))
    }
    private class Owner:LifecycleOwner {val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
}

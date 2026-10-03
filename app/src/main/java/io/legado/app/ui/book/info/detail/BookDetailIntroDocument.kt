package io.legado.app.ui.book.info.detail

import io.legado.app.ui.dict.DictionaryResultDocument
import io.legado.app.ui.dict.dictionaryResultDocument
import java.security.MessageDigest
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import io.legado.app.ui.book.info.introIndentRanges

enum class BookDetailIntroMode { Plain,Html,Markdown,Web }
data class BookDetailIntroDocument(val signature:String,val mode:BookDetailIntroMode,val content:String,
    val rich:DictionaryResultDocument?=null,val plain:AnnotatedString?=null) {
    val canCollapse:Boolean get()=mode!=BookDetailIntroMode.Web && content.isNotBlank()
}
/** Run this pure content compilation on IO; the resulting immutable document is ready for rendering. */
fun bookDetailIntroDocument(raw:String?):BookDetailIntroDocument {
    val text=raw.orEmpty()
    val signature=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    val tagged=when {
        text.startsWith("<useweb>")->BookDetailIntroMode.Web to 8
        text.startsWith("<usehtml>")->BookDetailIntroMode.Html to 9
        text.startsWith("<md>")->BookDetailIntroMode.Markdown to 4
        else->null
    }
    val last=text.lastIndexOf('<')
    if(tagged==null || last<tagged.second) {
        val plain=buildAnnotatedString {
            append(text)
            introIndentRanges(text).forEach{range->addStyle(ParagraphStyle(textIndent=TextIndent(firstLine=28.sp,restLine=0.sp)),range.start,range.endExclusive)}
        }
        return BookDetailIntroDocument(signature,BookDetailIntroMode.Plain,text,plain=plain)
    }
    val (mode,start)=tagged;val body=text.substring(start,last)
    val rich=when(mode){BookDetailIntroMode.Html->dictionaryResultDocument(body)
        BookDetailIntroMode.Markdown->dictionaryResultDocument("<md>$body</md>")
        else->null}
    return BookDetailIntroDocument(signature,mode,body,rich)
}

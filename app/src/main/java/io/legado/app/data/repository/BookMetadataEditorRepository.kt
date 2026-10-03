package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.hasEditedNetworkCover
import io.legado.app.help.book.isLocal
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/** Editable metadata only; reading progress and other mutable Book fields stay inside Room. */
data class BookMetadataSnapshot(val bookUrl:String,val name:String,val author:String,val type:Int,
    val origin:String,val coverUrl:String?,val customCoverUrl:String?,val persistedCoverUrl:String?,
    val intro:String?,val customIntro:String?) {
    val typeIndex:Int get()=when {
        type and BookType.video!=0->4
        type and BookType.image!=0->2
        type and BookType.audio!=0->1
        else->0
    }
    val coverText:String get()=customCoverUrl?.takeIf{it.isNotEmpty()} ?: coverUrl.orEmpty()
    val introText:String get()=customIntro?.takeIf{it.isNotEmpty()} ?: intro.orEmpty()
    fun preview(cover:String?=customCoverUrl,persisted:String?=persistedCoverUrl)=CoverRequest.from(Book(
        bookUrl=bookUrl,name=name,author=author,type=type,origin=origin,coverUrl=coverUrl,
        customCoverUrl=cover,persistedCoverUrl=persisted))
    companion object {
        fun from(book:Book)=BookMetadataSnapshot(book.bookUrl,book.name,book.author,book.type,
            book.origin,book.coverUrl,book.customCoverUrl,book.persistedCoverUrl,book.intro,book.customIntro)
    }
}
enum class BookMetadataField { Name,Author,Type,Cover,Intro }
data class BookMetadataInput(val bookUrl:String,val name:String,val author:String,val typeIndex:Int,
    val cover:String,val intro:String,val changed:Set<BookMetadataField> = emptySet(),val refreshCover:Boolean=false)
/** A durable session writes this plan before Room mutation so a post-commit file failure can retry safely. */
data class BookMetadataSave(val beforeJson:String,val targetJson:String)
class BookMetadataMissing:IllegalStateException("Book no longer exists")
class BookMetadataConflict:IllegalStateException("Book changed during save recovery")
interface BookMetadataEditorRepository {
    suspend fun load(bookUrl:String):BookMetadataSnapshot?
    suspend fun save(input:BookMetadataInput,journal:(BookMetadataSave)->Unit):BookMetadataSnapshot
    suspend fun recover(plan:BookMetadataSave):BookMetadataSnapshot
}

internal fun mergeBookMetadata(current:Book,input:BookMetadataInput):Book {
    require(current.bookUrl==input.bookUrl && input.typeIndex in 0..4)
    val changed=input.changed
    val cover=input.cover.takeIf{it.isNotEmpty()}
    val editableType=when(input.typeIndex){4->BookType.video;2->BookType.image;1->BookType.audio;else->BookType.text}
    val type=if(BookMetadataField.Type in changed) {
        val removed=BookType.video or BookType.text or BookType.image or BookType.audio or BookType.local
        (current.type and removed.inv()) or editableType or (if(current.isLocal)BookType.local else 0)
    } else current.type
    val coverEdited=BookMetadataField.Cover in changed || input.refreshCover
    return current.copy(
        name=if(BookMetadataField.Name in changed)input.name else current.name,
        author=if(BookMetadataField.Author in changed)input.author else current.author,
        type=type,
        customCoverUrl=if(coverEdited)cover?.takeUnless{it==current.coverUrl} else current.customCoverUrl,
        persistedCoverUrl=if(coverEdited && (input.refreshCover || hasEditedNetworkCover(cover,current.customCoverUrl,current.coverUrl)))null else current.persistedCoverUrl,
        customIntro=if(BookMetadataField.Intro in changed)input.intro.takeUnless{it==current.intro} else current.customIntro,
    )
}

class RoomBookMetadataEditorRepository(private val database:AppDatabase=appDb,
    private val moveCache:(Book,Book)->Unit=BookHelp::updateCacheFolder) : BookMetadataEditorRepository {
    override suspend fun load(bookUrl:String)=withContext(IO){database.bookDao.getBook(bookUrl)?.let(BookMetadataSnapshot::from)}
    override suspend fun save(input:BookMetadataInput,journal:(BookMetadataSave)->Unit)=withContext(IO) {
        var before:Book?=null;var target:Book?=null
        database.runInTransaction {
            val current=database.bookDao.getBook(input.bookUrl) ?: throw BookMetadataMissing()
            val next=mergeBookMetadata(current,input)
            journal(BookMetadataSave(GSON.toJson(current),GSON.toJson(next)))
            database.bookDao.update(next)
            database.bookHighlightDao.updateBookMetadata(next.bookUrl,next.name,next.author)
            before=current;target=next
        }
        // Preserve the existing best-effort rename helper, but perform it off Main and after validation.
        moveCache(checkNotNull(before),checkNotNull(target))
        BookMetadataSnapshot.from(checkNotNull(target))
    }
    override suspend fun recover(plan:BookMetadataSave)=withContext(IO) {
        val before=GSON.fromJsonObject<Book>(plan.beforeJson).getOrThrow()
        val target=GSON.fromJsonObject<Book>(plan.targetJson).getOrThrow()
        require(before.bookUrl==target.bookUrl)
        database.runInTransaction {
            val current=database.bookDao.getBook(target.bookUrl) ?: throw BookMetadataMissing()
            when(GSON.toJson(current)) {
                plan.targetJson->Unit
                plan.beforeJson->{
                    database.bookDao.update(target)
                    database.bookHighlightDao.updateBookMetadata(target.bookUrl,target.name,target.author)
                }
                else->throw BookMetadataConflict()
            }
        }
        // Never rewrite a concurrent edit during recovery; replay only the planned cache rename.
        moveCache(before,target)
        BookMetadataSnapshot.from(target)
    }
}

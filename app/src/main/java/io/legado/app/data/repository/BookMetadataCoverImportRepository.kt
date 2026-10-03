package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

interface BookMetadataCoverImportRepository {suspend fun install(uri:String):String}
class FileBookMetadataCoverImportRepository(context:Context,
    private val directory:File=File(context.applicationContext.externalFiles,"covers"),
    private val openStream:((Uri)->InputStream)?=null) : BookMetadataCoverImportRepository {
    private val context=context.applicationContext
    override suspend fun install(uri:String):String=withContext(Dispatchers.IO) {
        val source=Uri.parse(uri)
        if(source.scheme?.lowercase() in listOf("http","https"))return@withContext uri
        val local=when(source.scheme?.lowercase()){null,""->File(uri);"file"->source.path?.let(::File);else->null}
        val name=if(local!=null)local.name else DocumentFile.fromSingleUri(context,source)?.name
        check(!name.isNullOrEmpty()){ "未获取到文件" }
        val suffix=if(name.contains(".9.png",ignoreCase=true))".9.png" else "."+name.substringAfterLast('.')
        check(directory.isDirectory || directory.mkdirs()){ "无法创建封面目录" }
        val pending=File.createTempFile("book_cover_", ".part",directory)
        try {
            val digest=MessageDigest.getInstance("MD5")
            (openStream?.invoke(source) ?: local?.inputStream() ?: checkNotNull(context.contentResolver.openInputStream(source))).use{input->
                DigestOutputStream(pending.outputStream(),digest).use{output->input.copyTo(output)}
            }
            currentCoroutineContext().ensureActive()
            val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            val target=File(directory,hash+suffix)
            if(target.exists())pending.delete() else check(pending.renameTo(target)){ "无法保存封面" }
            target.absolutePath
        } finally{withContext(NonCancellable+Dispatchers.IO){pending.delete()}}
    }
}

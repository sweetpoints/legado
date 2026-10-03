package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.AppConst
import io.legado.app.utils.MD5Utils
import java.io.File
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class BookMetadataCoverImportRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File
    private lateinit var covers: File

    @Before
    fun before() {
        directory =
            File(context.cacheDir, "book-cover-import-test-" + UUID.randomUUID()).apply { mkdirs() }
        covers = File(directory, "covers")
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    @Test
    fun remoteUrlsKeepOriginalQueryAndRequireNoLocalFile() = runBlocking {
        val uri = "https://fixture.invalid/image.jpg?key=a%2Fb&size=100"
        assertEquals(uri, FileBookMetadataCoverImportRepository(context, covers).install(uri))
        assertFalse(covers.exists())
    }

    @Test
    fun actualContentProviderAndLocalNinePatchCopyPreserveMd5AndSuffixWithoutPartialFiles() =
        runBlocking {
            val source =
                File(directory, "Image.9.PNG").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val uri = FileProvider.getUriForFile(context, AppConst.authority, source)
            val repo = FileBookMetadataCoverImportRepository(context, covers)
            val content = File(repo.install(uri.toString()))
            val local = File(repo.install(Uri.fromFile(source).toString()))
            val hash = source.inputStream().use { MD5Utils.md5Encode(it) }
            assertEquals("$hash.9.png", content.name)
            assertEquals(content, local)
            assertArrayEquals(source.readBytes(), content.readBytes())
            assertEquals(listOf(content.name), covers.listFiles()!!.map { it.name })
        }

    @Test
    fun missingLocalInputLeavesNoInstalledCoverOrTemporaryPart() = runBlocking {
        val source = File(directory, "missing.jpg")
        assertTrue(
            runCatching {
                FileBookMetadataCoverImportRepository(context, covers)
                    .install(Uri.fromFile(source).toString())
            }
                .isFailure
        )
        assertTrue(covers.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationDuringActualCopyClosesInputAndRemovesPendingWithoutInstallingIt() =
        runBlocking {
            val source =
                File(directory, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = AtomicBoolean(false)
            val repo =
                FileBookMetadataCoverImportRepository(context, covers) {
                    object : FilterInputStream(source.inputStream()) {
                        private var waited = false

                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            if (!waited) {
                                waited = true
                                started.countDown()
                                check(release.await(5, TimeUnit.SECONDS))
                            }
                            return super.read(bytes, offset, length)
                        }

                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }
                }
            val work = launch(Dispatchers.Default) { repo.install(Uri.fromFile(source).toString()) }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                work.cancel()
                release.countDown()
                work.join()
                assertTrue(work.isCancelled)
                assertTrue(closed.get())
                assertTrue(covers.listFiles().orEmpty().isEmpty())
            } finally {
                release.countDown()
                work.cancelAndJoin()
            }
        }
}

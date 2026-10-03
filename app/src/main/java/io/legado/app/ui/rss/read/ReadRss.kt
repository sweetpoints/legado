package io.legado.app.ui.rss.read

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.model.rss.Rss
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ReadRss {
    /** 通过RSS历史记录点击阅读 */
    fun readRss(activity: AppCompatActivity, record: RssReadRecord) {
        val type = record.type
        if (type == 0) {
            ReadRssActivity.start(
                activity,
                record.origin,
                record.title,
                link = record.record,
                sort = record.sort,
            )
            return
        }
        if (type == 2) {
            activity.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", record.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", record.record)
            }
            return
        }
        readNoHtml(activity, record, type)
    }

    fun readRss(fragment: Fragment, rssArticle: RssArticle, rssSource: RssSource? = null) {
        val rssReadRecord = rssArticle.toRecord()
        appDb.rssReadRecordDao.insertRecord(rssReadRecord)
        val type = rssArticle.type
        if (type == 0) {
            // web网页
            ReadRssActivity.start(
                fragment.requireContext(),
                rssArticle.origin,
                rssArticle.title,
                link = rssArticle.link,
                sort = rssArticle.sort,
            )
            return
        }
        if (type == 2) {
            // 视频播放
            fragment.startActivity<VideoPlayerActivity> {
                putExtra("sourceKey", rssArticle.origin)
                putExtra("sourceType", SourceType.rss)
                putExtra("record", rssArticle.link)
            }
            return
        }
        readNoHtml(fragment, rssArticle, rssSource, type)
    }

    /** Compose callers prepare the read record on IO before dispatching the full article. */
    fun readRss(activity: AppCompatActivity, rssArticle: RssArticle, rssSource: RssSource? = null) {
        when (rssArticle.type) {
            0 ->
                ReadRssActivity.start(
                    activity,
                    rssArticle.origin,
                    rssArticle.title,
                    link = rssArticle.link,
                    sort = rssArticle.sort,
                )
            2 ->
                activity.startActivity<VideoPlayerActivity> {
                    putExtra("sourceKey", rssArticle.origin)
                    putExtra("sourceType", SourceType.rss)
                    putExtra("record", rssArticle.link)
                }
            else ->
                readArticleLink(activity.lifecycleScope, rssArticle, rssSource, rssArticle.type) {
                    url ->
                    showPhoto(activity, url)
                }
        }
    }

    private fun readNoHtml(
        fragment: Fragment,
        rssArticle: RssArticle,
        rssSource: RssSource? = null,
        type: Int,
    ) {
        val source = rssSource ?: appDb.rssSourceDao.getByKey(rssArticle.origin)
        readArticleLink(fragment.viewLifecycleOwner.lifecycleScope, rssArticle, source, type) { url
            ->
            withContext(Dispatchers.Main.immediate) {
                val owner = fragment.viewLifecycleOwner
                val context = currentCoroutineContext()
                context.ensureActive()
                owner.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                    context.ensureActive()
                    if (fragment.isAdded && !fragment.parentFragmentManager.isStateSaved)
                        fragment.showDialogFragment(PhotoDialog(url))
                }
            }
        }
    }

    private fun readArticleLink(
        scope: CoroutineScope,
        rssArticle: RssArticle,
        source: RssSource?,
        type: Int,
        showPhoto: suspend (String) -> Unit,
    ) {
        source?.let { s ->
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                if (type == 1) scope.launch { showPhoto(rssArticle.link) }
            } else {
                Rss.getContent(scope, rssArticle, ruleContent, s)
                    .onSuccess(IO) { body ->
                        if (body.isBlank()) throw ContentEmptyException("正文为空")
                        val url = NetworkUtils.getAbsoluteURL(rssArticle.link, body)
                        if (type == 1) showPhoto(url)
                    }
                    .onError { AppLog.put("加载为链接的正文失败", it, true) }
            }
        }
    }

    private suspend fun showPhoto(activity: AppCompatActivity, url: String) =
        withContext(Dispatchers.Main.immediate) {
            val context = currentCoroutineContext()
            context.ensureActive()
            activity.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {
                context.ensureActive()
                if (!activity.isFinishing && !activity.supportFragmentManager.isStateSaved)
                    activity.showDialogFragment(PhotoDialog(url))
            }
        }

    private fun readNoHtml(activity: AppCompatActivity, record: RssReadRecord, type: Int) {
        val rssSource = appDb.rssSourceDao.getByKey(record.origin)
        rssSource?.let { s ->
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                when (type) {
                    1 -> activity.lifecycleScope.launch { showPhoto(activity, record.record) }
                }
            } else {
                Rss.getContent(activity.lifecycleScope, record.toRssArticle(), ruleContent, s)
                    .onSuccess(IO) { body ->
                        val url = NetworkUtils.getAbsoluteURL(record.record, body)
                        when (type) {
                            1 -> showPhoto(activity, url)
                        }
                    }
                    .onError {
                        AppLog.put("加载为链接的正文失败", it, true)
                    }
            }
        }
    }
}

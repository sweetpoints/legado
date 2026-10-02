package io.legado.app.ui.about

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import java.io.File
import kotlinx.coroutines.delay
import splitties.init.appCtx

/**
 * 「关于」页 —— Compose 迁移的参考样板。
 *
 * 相比最初的实现：
 * * 不再需要 `activity_about.xml`（通过 Activity.setContent 创建组合）
 * * 不再需要 `AboutFragment`（PreferenceFragmentCompat）与 `R.xml.about`，
 *   偏好列表已由 [AboutScreen] 用纯 Compose 重写
 * * 「公众号高亮」由原来的 `ForegroundColorSpan` + `post {}` 改为 Compose 的
 *   `buildAnnotatedString`，不再依赖 View 测量完成时机
 * * 右上角两个动作由 `R.menu.about` + `TitleBar` 改为 Compose 顶栏
 *
 * 原先散落在 Fragment 里的业务逻辑（Markdown 对话框、日志与堆转储导出）随之迁到这里。
 */
class AboutActivity : BaseComposeActivity() {

    @Composable
    override fun Content(savedInstanceState: Bundle?) {
        AboutScreen(
            version = appInfo.versionName,
            onBack = { finish() },
            onShare = {
                share(
                    getString(R.string.app_share_description),
                    getString(R.string.app_name)
                )
            },
            onScoring = { openUrl("market://details?id=$packageName") },
            onItemClick = ::onAboutItemClick,
        )
    }

    private fun onAboutItemClick(key: String) {
        when (key) {
            "contributors" -> openUrl(getString(R.string.contributors_url))
            "update_log" -> showMdFile(getString(R.string.update_log), "updateLog.md")
            "check_update" -> checkAppUpdate()
            "check_beta_update" -> checkAppUpdate(beta = true)
            "license" -> showMdFile(getString(R.string.license), "LICENSE.md")
            "disclaimer" -> showMdFile(getString(R.string.disclaimer), "disclaimer.md")
            "privacyPolicy" -> showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
            "crashLog" -> showDialogFragment<CrashLogsDialog>()
            "saveLog" -> saveLog()
            "createHeapDump" -> createHeapDump()
        }
    }

    /**
     * 显示 md 文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }

    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }

}

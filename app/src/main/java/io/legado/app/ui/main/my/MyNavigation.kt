package io.legado.app.ui.main.my

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.lib.dialogs.selector
import io.legado.app.service.McpService
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.about.checkAppUpdate
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity

/** Platform navigation shared by the main destination and its More entry point. */
internal fun AppCompatActivity.openMyItem(key: String) {
    when (key) {
        "myMore" -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.MY_MORE) }
        "check_update" -> checkAppUpdate()
        "check_beta_update" -> checkAppUpdate(beta = true)
        "bookSourceManage" -> startActivity<BookSourceActivity>()
        "autoTaskManage" -> startActivity<AutoTaskActivity>()
        "replaceManage" -> startActivity<ReplaceRuleActivity>()
        "dictRuleManage" -> startActivity<DictRuleActivity>()
        "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
        "bookmark" -> startActivity<AllBookmarkActivity>()
        "setting" -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.OTHER_CONFIG) }
        "web_dav_setting" -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.BACKUP_CONFIG) }
        "theme_setting" -> startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.THEME_CONFIG) }
        "fileManage" -> startActivity<FileManageActivity>()
        "readRecord" -> startActivity<ReadRecordActivity>()
        "about" -> startActivity<AboutActivity>()
        "exit" -> finish()
    }
}

internal fun AppCompatActivity.showMyServiceActions(key: String) {
    when (key) {
        PreferKey.webService -> if (WebService.isRun) {
            selector(arrayListOf(getString(R.string.copy_url), getString(R.string.open_in_browser))) { _, index ->
                if (index == 0) sendToClip(WebService.hostAddress) else openUrl(WebService.hostAddress)
            }
        }
        PreferKey.mcpService -> if (McpService.isRun) sendToClip(McpService.hostAddress)
    }
}

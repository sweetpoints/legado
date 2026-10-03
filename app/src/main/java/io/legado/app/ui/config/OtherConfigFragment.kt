package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.*
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.model.ImageProvider
import io.legado.app.model.settings.*
import io.legado.app.service.McpService
import io.legado.app.service.WebService
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

class OtherConfigFragment : Fragment(), ConfigSearchPage {
    private val config by activityViewModels<ConfigViewModel>()
    private val model by viewModels<OtherSettingsViewModel> { viewModelFactory { initializer {
        val application = requireContext().applicationContext
        OtherSettingsViewModel(DefaultOtherSettingsRepository(AppOtherSettingsStore(application)), FileOtherSettingsDraftRepository(application), createSavedStateHandle())
    } } }
    private var query by mutableStateOf<String?>(null); private var selected: (() -> Unit)? = null
    private val bookTree = registerForActivityResult(HandleFileContract()) { result -> model.pickedBookTree(result.uri?.toString(), result.value ?: model.bookTreeTicket()) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { LegadoComposeTheme { OtherSettingsRoute(model, { isAdded && !parentFragmentManager.isStateSaved }, ::handleEffect,
            { id -> bookTree.launch { title = getString(R.string.select_book_folder); mode = HandleFileContract.DIR_SYS; value = id } }, ::destination, query,
            { query = null; selected?.invoke(); selected = null }, { query = null; selected = null; toastOnUi(R.string.config_search_empty) }) } }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { super.onViewCreated(view, savedInstanceState); activity?.setTitle(R.string.other_setting) }
    override fun searchSettings(query: String, onSelected: () -> Unit) { this.query = query; selected = onSelected }
    private fun destination(value: OtherDestination) {
        when (value) {
            OtherDestination.CheckSource -> showDialogFragment<CheckSourceConfig>()
            OtherDestination.Upload -> showDialogFragment<DirectLinkUploadConfig>()
            OtherDestination.Video -> showDialogFragment(SettingsDialog(requireActivity()))
            OtherDestination.ClearCache -> config.clearCache()
            OtherDestination.ClearWeb -> config.clearWebViewData()
            OtherDestination.Shrink -> config.shrinkDatabase()
        }
    }
    private fun handleEffect(receipt: OtherEffectReceipt) {
        val application = requireContext().applicationContext
        when (receipt.effect) {
            OtherEffect.ProcessTextConfiguration -> {
                val captured = model
                Coroutine.async(context = Dispatchers.IO) { captured.reconcileProcessText() }.onError { AppLog.put("同步系统文本处理设置失败", it) }
            }
            OtherEffect.ThreadsChanged -> postEvent(PreferKey.threadCount, "")
            OtherEffect.RestartWeb -> if (WebService.isRun) { WebService.stop(application); WebService.start(application) }
            OtherEffect.RestartMcp -> if (McpService.isRun) McpService.restart(application)
            OtherEffect.StopMcp -> if (McpService.isRun) McpService.stop(application)
            OtherEffect.NotifyMain -> postEvent(EventBus.NOTIFY_MAIN, true)
            OtherEffect.ResizeBitmapCache -> ImageProvider.bitmapLruCache.resize((model.state.value.settings?.numbers?.get(OtherNumber.BitmapCache) ?: 50).coerceIn(1, 1024) * 1024 * 1024)
            OtherEffect.DownloadCronet -> Coroutine.async(context = Dispatchers.IO) { Cronet.preDownload() }.onError { AppLog.put("预下载 Cronet 失败", it) }
            OtherEffect.RestartApplication -> Coroutine.async(context = Dispatchers.Main.immediate) { delay(1000); application.restart() }
            OtherEffect.LogConfiguration -> {
                AppConfig.recordLog = model.state.value.settings?.switches?.get(OtherSwitch.Log) ?: false
                LogUtils.upLevel(); LiveEventBus.config().enableLogger(AppConfig.recordLog); AppFreezeMonitor.init(application); DispatchersMonitor.init()
                Coroutine.async(context = Dispatchers.IO) { LogUtils.logDeviceInfo() }.onError { AppLog.put("记录设备信息失败", it) }
            }
            OtherEffect.PromotedNotificationSettings -> if (supportsPromotedNotifications() && !NotificationManagerCompat.from(application).canPostPromotedNotifications()) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, application.packageName)
                val opened = try {
                    if (intent.resolveActivity(application.packageManager) == null) false
                    else { startActivity(intent); true }
                } catch (_: android.content.ActivityNotFoundException) { false }
                catch (_: SecurityException) { false }
                if (!opened) { model.promotedNotificationUnavailable(); toastOnUi(R.string.tip_cannot_jump_setting_page) }
            }
        }
    }
    override fun onStop() { val captured = model
        Coroutine.async(context = Dispatchers.Main.immediate) { captured.flush() }.onError { AppLog.put("保存其它设置草稿失败", it) }; super.onStop() }
    override fun onDestroy() {
        if (isRemoving || activity?.isFinishing == true) { val captured = model; captured.stop()
            Coroutine.async(context = Dispatchers.Main.immediate) { captured.release() }.onError { AppLog.put("清理其它设置草稿失败", it) } }
        selected = null; super.onDestroy()
    }
}

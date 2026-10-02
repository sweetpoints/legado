package io.legado.app.ui.login

import android.content.Intent
import io.legado.app.data.repository.*
import io.legado.app.model.login.LoginUiV2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A restored Fragment can be composed before its activity finishes loading the source. */
internal class PendingSourceLoginFormRepository(private val sourceModel: SourceLoginViewModel,
    private val intent: Intent) : SourceLoginFormRepository {
    private var delegate: AppSourceLoginFormRepository? = null
    override val definition: SourceLoginDefinition get() = if (sourceModel.initialization.value == SourceLoginInitialization.Ready)
        SourceLoginDefinition(sourceModel.source?.getTag().orEmpty(), sourceModel.source?.isLoginUiV2() == true, sourceModel.loginInfo.toMap())
        else SourceLoginDefinition("", false, emptyMap())
    private suspend fun resolved(): AppSourceLoginFormRepository {
        delegate?.let { return it }
        val status = withTimeoutOrNull(30_000) { sourceModel.initialization.first {
            it == SourceLoginInitialization.Ready || it is SourceLoginInitialization.Failed
        } } ?: error("来源登录初始化超时")
        if (status is SourceLoginInitialization.Failed) error(status.message)
        val source = sourceModel.source ?: error("未找到书源")
        return AppSourceLoginFormRepository(source, sourceModel.book, sourceModel.chapter, sourceModel.loginInfo).also { delegate = it }
    }
    override suspend fun ready() = resolved().definition
    override fun retry() { delegate = null; sourceModel.initData(Intent(intent), {}, {}) }
    override suspend fun render(values: Map<String, String>, stateJson: String) = resolved().render(values, stateJson)
    override suspend fun label(script: String, values: Map<String, String>) = resolved().label(script, values)
    override suspend fun legacyAction(script: String, values: Map<String, String>, long: Boolean, java: Any) = resolved().legacyAction(script, values, long, java)
    override suspend fun legacyLogin(values: Map<String, String>, java: Any) = resolved().legacyLogin(values, java)
    override suspend fun action(action: String, stateJson: String, values: Map<String, String>): LoginUiV2.ActionResult = resolved().action(action, stateJson, values)
    override suspend fun store(json: String) = resolved().store(json)
    override suspend fun persist(values: Map<String, String>) = resolved().persist(values)
    override suspend fun header() = resolved().header()
    override suspend fun deleteHeader() = resolved().deleteHeader()
    override suspend fun clear() = resolved().clear()
}

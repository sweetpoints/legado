package io.legado.app.data.repository

import io.legado.app.help.TextSelectMenuConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reader callbacks map these value-only actions to their existing Android resource IDs. */
internal enum class TextActionKind(val configKey: String) {
    Replace(TextSelectMenuConfig.KEY_REPLACE),
    Copy(TextSelectMenuConfig.KEY_COPY),
    Bookmark(TextSelectMenuConfig.KEY_BOOKMARK),
    Highlight(TextSelectMenuConfig.KEY_HIGHLIGHT),
    Aloud(TextSelectMenuConfig.KEY_ALOUD),
    Dict(TextSelectMenuConfig.KEY_DICT),
    Search(TextSelectMenuConfig.KEY_SEARCH),
    Browser(TextSelectMenuConfig.KEY_BROWSER),
    Share(TextSelectMenuConfig.KEY_SHARE),
    ProcessText(TextSelectMenuConfig.KEY_PROCESS_TEXT),
}

internal data class TextProcessTarget(
    val packageName: String,
    val className: String,
    val title: String,
)

internal data class TextAction(
    val id: String,
    val kind: TextActionKind,
    val title: String,
    val process: TextProcessTarget? = null,
)

internal data class TextActionSnapshot(
    val primary: List<TextAction>,
    val more: List<TextAction>,
    val discoveryError: String? = null,
)

internal interface TextActionStore {
    suspend fun config(): TextSelectMenuConfig

    suspend fun titles(): Map<TextActionKind, String>

    suspend fun processTargets(): List<TextProcessTarget>

    suspend fun speakMode(): Int

    suspend fun setSpeakMode(value: Int)
}

internal interface TextActionRepository {
    suspend fun load(): TextActionSnapshot

    suspend fun toggleSpeakMode(): Int
}

internal class DefaultTextActionRepository(
    private val store: TextActionStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TextActionRepository {
    private val speakLock = Mutex()

    override suspend fun load() =
        withContext(io) {
            val config = store.config()
            val titles = store.titles()
            var error: String? = null
            val process =
                try {
                    store.processTargets()
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (failure: Exception) {
                    error = failure.localizedMessage.orEmpty()
                    emptyList()
                }
            val builtIn =
                TextActionKind.entries
                    .filterNot { it == TextActionKind.ProcessText }
                    .map { kind ->
                        TextAction("builtin:${kind.configKey}", kind, titles[kind].orEmpty())
                    }
            val occurrences = mutableMapOf<String, Int>()
            val external = process.map { target ->
                val component = "${target.packageName}/${target.className}"
                val occurrence = occurrences[component] ?: 0
                occurrences[component] = occurrence + 1
                TextAction(
                    "process:$component#$occurrence",
                    TextActionKind.ProcessText,
                    target.title,
                    target,
                )
            }
            partitionTextActions(config, builtIn, external).copy(discoveryError = error)
        }

    override suspend fun toggleSpeakMode() =
        withContext(io) {
            speakLock.withLock {
                val next = if (store.speakMode() == 0) 1 else 0
                store.setSpeakMode(next)
                next
            }
        }
}

internal fun partitionTextActions(
    config: TextSelectMenuConfig,
    builtIn: List<TextAction>,
    process: List<TextAction>,
): TextActionSnapshot {
    val partition =
        config.partitionItems(builtIn.associateBy { it.kind.configKey }, process, builtIn + process)
    return TextActionSnapshot(partition.bar.toList(), partition.more.toList())
}

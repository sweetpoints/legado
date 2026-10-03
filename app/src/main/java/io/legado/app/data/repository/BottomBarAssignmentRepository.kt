package io.legado.app.data.repository

import android.graphics.Bitmap
import io.legado.app.help.BottomBarSkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BottomBarAssignmentSlot(
    val slot: String,
    val selected: String? = null,
    val normal: String? = null,
)

data class BottomBarAssignmentImages(
    val images: List<String>,
    val slots: List<BottomBarAssignmentSlot>,
)

interface BottomBarAssignmentRepository {
    suspend fun load(session: String, sizePx: Int): BottomBarAssignmentImages

    suspend fun preview(session: String, image: String, sizePx: Int): Bitmap?

    suspend fun save(
        session: String,
        name: String,
        editName: String?,
        slots: List<BottomBarAssignmentSlot>,
    ): String

    suspend fun discard(session: String)
}

/** Projects private staging files to filename IDs; the existing manager owns skin transactions. */
class AppBottomBarAssignmentRepository : BottomBarAssignmentRepository {
    private val previews = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private fun previewKey(session: String, image: String, size: Int) = "$session:$size:$image"

    override suspend fun load(session: String, sizePx: Int) =
        withContext(Dispatchers.IO) {
            val images =
                BottomBarSkinManager.stagingImages(session).filter { file ->
                    BottomBarSkinManager.previewBitmap(file, sizePx)?.let {
                        previews[previewKey(session, file.name, sizePx)] = it
                        true
                    } ?: false
                }
            val prefill = BottomBarSkinManager.buildPrefill(images)
            BottomBarAssignmentImages(
                images.map { it.name },
                slots.map { slot ->
                    BottomBarAssignmentSlot(
                        slot,
                        prefill[slot]?.selected?.name,
                        prefill[slot]?.normal?.name,
                    )
                },
            )
        }

    override suspend fun preview(session: String, image: String, sizePx: Int) =
        withContext(Dispatchers.IO) {
            previews[previewKey(session, image, sizePx)]
                ?: BottomBarSkinManager.stagingImages(session)
                    .find { it.name == image }
                    ?.let { BottomBarSkinManager.previewBitmap(it, sizePx) }
                    ?.also { previews[previewKey(session, image, sizePx)] = it }
        }

    override suspend fun save(
        session: String,
        name: String,
        editName: String?,
        slots: List<BottomBarAssignmentSlot>,
    ) =
        withContext(Dispatchers.IO) {
            val files = BottomBarSkinManager.stagingImages(session).associateBy { it.name }
            val assignments = linkedMapOf<String, BottomBarSkinManager.SlotAssign>()
            require(slots.map { it.slot } == Companion.slots)
            slots.forEach { row ->
                row.selected?.let { selected ->
                    assignments[row.slot] =
                        BottomBarSkinManager.SlotAssign(
                            checkNotNull(files[selected]),
                            row.normal?.let { checkNotNull(files[it]) },
                        )
                }
            }
            BottomBarSkinManager.saveSkin(name.trim(), assignments, session, editName).getOrThrow()
        }

    override suspend fun discard(session: String) =
        withContext(Dispatchers.IO) { BottomBarSkinManager.discardSession(session) }

    companion object {
        val slots = listOf("bookshelf", "home", "notes", "settings")
    }
}

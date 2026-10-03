package io.legado.app.model

/**
 * The engine has one Activity owner; destroying a retired host must leave its replacement alone.
 */
internal fun releaseAudioController(
    currentOwner: Any?,
    retiringOwner: Any,
    release: () -> Unit,
): Boolean {
    if (currentOwner !== retiringOwner) return false
    release()
    return true
}

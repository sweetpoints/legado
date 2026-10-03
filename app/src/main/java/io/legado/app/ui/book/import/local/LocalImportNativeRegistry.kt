package io.legado.app.ui.book.import.local

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import io.legado.app.ui.file.HandleFileContract

/** Each picker callback captures its own private receipt, including valueless cancel results. */
internal class LocalImportNativeRegistry(
    private val registry: ActivityResultRegistry,
    private val result: (LocalImportNative, HandleFileContract.Result) -> Unit,
) {
    private val launchers =
        mutableMapOf<
            String,
            ActivityResultLauncher<(HandleFileContract.HandleFileParam.() -> Unit)?>,
        >()

    fun register(
        receipt: LocalImportNative
    ): ActivityResultLauncher<(HandleFileContract.HandleFileParam.() -> Unit)?> {
        val key = "local-import.${receipt.kind}.${receipt.nonce}"
        return launchers[key]
            ?: registry
                .register(key, HandleFileContract()) { value ->
                    // The registry key, rather than a mutable latest nonce, identifies cancel
                    // ownership.
                    if (value.value == null || value.value == receipt.nonce) result(receipt, value)
                    launchers.remove(key)?.unregister()
                }
                .also { launchers[key] = it }
    }

    fun close() {
        launchers.values.forEach { it.unregister() }
        launchers.clear()
    }
}

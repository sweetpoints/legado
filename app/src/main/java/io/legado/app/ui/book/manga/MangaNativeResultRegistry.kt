package io.legado.app.ui.book.manga

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import java.util.UUID

/** Per-operation registry keys retain the trusted UUID even after another reader session opens. */
internal class MangaNativeResultRegistry(private val registry: ActivityResultRegistry) {
    private val launchers = mutableMapOf<String, ActivityResultLauncher<*>>()
    private val deliveredDuringRegistration = mutableSetOf<String>()

    @Suppress("UNCHECKED_CAST")
    fun <Input, Result> launcher(
        kind: String,
        ticket: String,
        contract: ActivityResultContract<Input, Result>,
        receive: (String, Result) -> Unit,
    ): ActivityResultLauncher<Input> {
        require(UUID.fromString(ticket).toString() == ticket)
        val key = "manga.native.$kind.$ticket"
        launchers[key]?.let {
            return it as ActivityResultLauncher<Input>
        }
        val launcher =
            registry.register(key, contract) { result ->
                // Capture this registration's UUID; never reinterpret a late result as the latest
                // one.
                receive(ticket, result)
                val registered = launchers.remove(key)
                if (registered != null) registered.unregister()
                else deliveredDuringRegistration.add(key)
            }
        // Restored pending results may be delivered synchronously by register().
        if (deliveredDuringRegistration.remove(key)) launcher.unregister()
        else launchers[key] = launcher
        return launcher
    }

    fun unregister(kind: String, ticket: String) {
        launchers.remove("manga.native.$kind.$ticket")?.unregister()
    }

    fun close() {
        launchers.values.toList().forEach { it.unregister() }
        launchers.clear()
        deliveredDuringRegistration.clear()
    }
}

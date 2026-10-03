package io.legado.app.ui.browser

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import java.util.UUID

/** Keeps every native picker callback bound to the UUID that launched it. */
internal class BrowserNativeResultRegistry(private val registry: ActivityResultRegistry) {
    private val launchers = mutableMapOf<String, ActivityResultLauncher<*>>()
    private val deliveredDuringRegistration = mutableSetOf<String>()

    @Suppress("UNCHECKED_CAST")
    fun <Input, Result> launcher(
        ticket: String,
        contract: ActivityResultContract<Input, Result>,
        receive: (String, Result) -> Unit,
    ): ActivityResultLauncher<Input> {
        require(UUID.fromString(ticket).toString() == ticket)
        val key = "browser.native.image.$ticket"
        launchers[key]?.let {
            return it as ActivityResultLauncher<Input>
        }
        val launcher =
            registry.register(key, contract) { result ->
                receive(ticket, result)
                val registered = launchers.remove(key)
                if (registered != null) registered.unregister()
                else deliveredDuringRegistration.add(key)
            }
        if (deliveredDuringRegistration.remove(key)) launcher.unregister()
        else launchers[key] = launcher
        return launcher
    }

    fun close() {
        launchers.values.toList().forEach { it.unregister() }
        launchers.clear()
        deliveredDuringRegistration.clear()
    }
}

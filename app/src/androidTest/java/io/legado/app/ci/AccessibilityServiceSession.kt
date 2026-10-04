package io.legado.app.ci

import android.Manifest
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.provider.Settings

/** Restores the device's original accessibility configuration even when a regression fails. */
internal class AccessibilityServiceSession(private val instrumentation: Instrumentation) : AutoCloseable {
    val automation: UiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val component = ComponentName(instrumentation.context, RegressionAccessibilityService::class.java)
    private val resolver = instrumentation.targetContext.contentResolver
    private val originalServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    private val originalEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)

    init {
        write((originalServices.orEmpty().split(':').filter(String::isNotBlank) + component.flattenToString()).distinct().joinToString(":"), "1")
    }

    private fun write(services: String?, enabled: String?) {
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services))
            check(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled))
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    override fun close() = write(originalServices, originalEnabled)
}

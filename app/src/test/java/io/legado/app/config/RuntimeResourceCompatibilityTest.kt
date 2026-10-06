package io.legado.app.config

import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeResourceCompatibilityTest {

    @Test
    fun `kotlin builtins loaders are absent from the runtime`() {
        kotlinBuiltinsRuntimeClasses.forEach { className ->
            assertThrows(className, ClassNotFoundException::class.java) {
                Class.forName(className)
            }
        }
    }

    private companion object {
        val kotlinBuiltinsRuntimeClasses =
            listOf(
                "kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader",
                "kotlin.reflect.jvm.internal.impl.builtins.KotlinBuiltIns",
                "kotlin.reflect.jvm.internal.impl.serialization.deserialization.builtins.BuiltInsResourceLoader",
            )
    }
}

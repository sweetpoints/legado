package io.legado.source.source_platform
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.mockito.Mockito
import kotlin.test.Test
internal class SourcePlatformPluginTest {
    @Test fun unsupportedMethodIsExplicit() {
        val result = Mockito.mock(MethodChannel.Result::class.java)
        SourcePlatformPlugin().onMethodCall(MethodCall("unknown", null), result)
        Mockito.verify(result).notImplemented()
    }
}

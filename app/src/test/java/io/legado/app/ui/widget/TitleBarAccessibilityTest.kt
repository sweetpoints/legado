package io.legado.app.ui.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TitleBarAccessibilityTest {

    @Test
    fun `back navigation uses the localized app label`() {
        val titleBar = projectFile(
            "src/main/java/io/legado/app/ui/widget/TitleBar.kt"
        ).readText()
        assertTrue(titleBar.contains("?: context.getText(R.string.back)"))
        assertTrue(
            titleBar.contains(
                "setHomeActionContentDescription(navigationDescription)"
            )
        )

        // Compose book navigation semantics are exercised by BookSourceComposeTest.

    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        // 与项目里其它测试保持一致：用 app/src/main 作为「仓库根」的标记。
        //
        // 原实现是「向上找第一个含 app 子目录的祖先」，这个启发式不可靠：
        // release 构建会产出 app/app/（项目自 2022 年起就在 .gitignore 里忽略它），
        // 于是它会停在 app/ 上，进而去读 app/app/src/... 而报 FileNotFoundException——
        // 表现为「单测是否通过取决于它和 assembleRelease 的先后顺序」。
        //
        // 注意：本类调用方传的是模块内相对路径（src/main/...），所以这里要返回 app 模块目录。
        val projectRoot = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(File(projectRoot, "app"), path)
    }
}

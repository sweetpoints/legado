package io.legado.app.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebWorkflowTest {

    private val workflowText by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val workflowFile = generateSequence(File(userDir)) {
            it.parentFile
        }.map {
            File(it, ".github/workflows/web.yml")
        }.first { it.isFile }
        workflowFile.readText().replace("\r\n", "\n")
    }

    @Test
    fun `web builds serialize generated asset updates`() {
        assertTrue(workflowText.contains("- '.github/workflows/web.yml'"))
        assertTrue(workflowText.contains("- '.github/scripts/push-web-assets.sh'"))
        assertTrue(workflowText.contains("group: build-web-" + "$" + "{{ github.ref }}"))
        assertTrue(workflowText.contains("cancel-in-progress: ${'$'}{{ github.event_name == 'pull_request' }}"))
        assertTrue(workflowText.contains("fetch-depth: 0"))
    }

    @Test
    fun `generated web assets use pull requests on the protected branch`() {
        assertTrue(workflowText.contains("github.event_name == 'push'"))
        assertTrue(workflowText.contains("github.ref == 'refs/heads/master'"))
        assertTrue(workflowText.contains("vars.ENABLE_WEB_ASSET_PR == 'true'"))
        assertTrue(workflowText.contains("uses: peter-evans/create-pull-request@v8"))
        assertTrue(workflowText.contains("branch: codex/web-assets"))
        assertTrue(workflowText.contains("base: master"))
        assertTrue(workflowText.contains("secrets.WEB_ASSET_TOKEN"))
        assertTrue(workflowText.contains("pull-requests: write"))
        assertFalse(workflowText.contains("run: bash .github/scripts/push-web-assets.sh"))
        assertFalse(workflowText.contains("gh workflow run TestRelease.yml"))
    }
}

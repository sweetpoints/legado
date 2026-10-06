package io.legado.app.web.mcp

import io.legado.app.data.entities.BookSource
import java.io.File
import java.net.URI
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServiceContractTest {

    @Test
    fun `transport authenticates before installing protected MCP route`() {
        val application = projectFile("app/src/main/java/io/legado/app/web/mcp/McpApplication.kt")
        val auth = application.indexOf("BookSourceController.matchesJsSourceApiToken")
        val route = application.indexOf("mcpStreamableHttp(")

        assertTrue(auth >= 0)
        assertTrue(route > auth)
        assertTrue(application.contains("context.request.header(McpAccess.TOKEN_HEADER)"))
        assertTrue(application.contains("HttpStatusCode.Unauthorized"))
        assertTrue(application.contains("allowedHosts = allowedHosts"))
        assertTrue(application.contains("allowedOrigins = allowedOrigins"))
        assertFalse(application.contains("enableDnsRebindingProtection = false"))
        assertFalse(application.contains("request.path()"))
        assertTrue(application.contains("tokenRequiredProvider()"))

        val service = projectFile("app/src/main/java/io/legado/app/service/McpService.kt")
        assertTrue(
            service.indexOf("AppConfig.jsSourceApiTokenRequired && token.isNullOrBlank()") <
                service.indexOf("embeddedServer(")
        )
        assertTrue(
            service.contains("tokenRequiredProvider = { AppConfig.jsSourceApiTokenRequired }")
        )

        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("foregroundServiceType=\"specialUse\""))
        assertTrue(manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))

        // Actual token change/clear plans and paused Main delivery are covered by
        // OtherSettingsViewModelTest and OtherSettingsRouteTest, without mirroring the settings
        // host.

        val network =
            projectFile("app/src/main/java/io/legado/app/receiver/NetworkChangedListener.kt")
        assertTrue(network.contains("includeDetailedChanges: Boolean = false"))
        assertTrue(network.contains("onLinkPropertiesChanged"))
        assertTrue(network.contains("onLost"))
        assertTrue(service.contains("activeAddressKeys"))
        assertTrue(service.contains("includeDetailedChanges = true"))
        assertTrue(service.contains("private var destroyed = false"))
        assertTrue(service.contains("if (destroyed) return"))
        assertTrue(Regex("""@Synchronized\s+override fun onDestroy\(\)""").containsMatchIn(service))

        val build = projectFile("app/build.gradle.kts")
        assertTrue(build.contains("module = \"kotlin-reflect\""))
    }

    @Test
    fun `server exposes the expected thirteen tools on current safe APIs`() {
        val tools = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")
        val registrations = tools.substringAfter("private fun registerTools")
        val names =
            Regex("name = \\\"([a-z_]+)\\\"")
                .findAll(registrations)
                .map { it.groupValues[1] }
                .toList()

        assertEquals(
            listOf(
                "save_source",
                "debug_source",
                "list_sources",
                "get_source",
                "delete_sources",
                "get_http_logs",
                "get_http_log",
                "set_http_log_recording",
                "get_cookies",
                "set_cookie",
                "clear_cookies",
                "eval_js",
                "check_source",
            ),
            names,
        )
        val getCookiesTool =
            tools.substringAfter("name = \"get_cookies\"").substringBefore("name = \"set_cookie\"")
        assertTrue(getCookiesTool.contains("CookieManager.getCookieNoSession"))
        assertTrue(getCookiesTool.contains("CookieManager.getSessionCookie"))
        assertTrue(getCookiesTool.contains("CookieManager.mergeCookies"))
        assertTrue(getCookiesTool.contains("McpFormat.truncate"))
        assertFalse(getCookiesTool.contains("CookieStore.getCookie("))

        val setCookieTool =
            tools
                .substringAfter("name = \"set_cookie\"")
                .substringBefore("name = \"clear_cookies\"")
        assertTrue(setCookieTool.contains("CookieStore.cookieToMap(cookie)"))
        assertTrue(setCookieTool.contains("cookieMap.isEmpty()"))
        assertTrue(setCookieTool.contains("cookieMap.keys.any"))
        assertTrue(setCookieTool.contains("有效的 name=value"))
        assertTrue(setCookieTool.contains("CookieStore.replaceCookie"))

        val clearCookiesTool =
            tools.substringAfter("name = \"clear_cookies\"").substringBefore("name = \"eval_js\"")
        assertTrue(clearCookiesTool.contains("CookieStore.removeCookie"))
        assertTrue(tools.contains("HttpLogRecord"))
        assertTrue(tools.contains("JsSourceUpsert.withSaveLock"))
        assertTrue(tools.contains("catch (error: CancellationException)"))
        assertTrue(tools.contains("val logs = data[\"logs\"] as List<*>"))
        assertTrue(tools.contains("val log = item as Map<*, *>"))
        assertTrue(tools.contains("Debug.tryStartCheckSession()"))
        assertTrue(tools.contains("Debug.isCheckServiceStarted(checkSessionId)"))
        assertTrue(tools.contains("CheckSource.stop(appCtx, checkSessionId)"))
        assertTrue(tools.contains("IntentData.get<Any>(selectedSourcesKey)"))
        assertFalse(tools.contains("UNCHECKED_CAST"))
        assertFalse(tools.contains("HttpRecord"))
        assertFalse(tools.contains("HttpLogger"))
        assertTrue(tools.contains("logging = ServerCapabilities.Logging"))

        val debugTool =
            tools
                .substringAfter("name = \"debug_source\"")
                .substringBefore("name = \"list_sources\"")
        assertTrue(debugTool.contains("Channel<String>(Channel.CONFLATED)"))
        assertTrue(debugTool.contains("request.meta?.progressToken"))
        assertTrue(debugTool.contains("notificationJob.join()"))
        assertFalse(debugTool.contains("Channel.UNLIMITED"))

        val notification =
            tools
                .substringAfter("private suspend fun sendBestEffort")
                .substringBefore("private suspend fun ClientConnection.sendProgressLine")
        assertTrue(notification.contains("catch (error: CancellationException)"))
        assertTrue(notification.contains("throw error"))

        val checkProgress =
            tools
                .substringAfter("private suspend fun ClientConnection.sendCheckProgress(")
                .substringBefore("private fun registerTools")
        assertTrue(checkProgress.contains("total = total"))

        val api = projectFile("api.md")
        assertTrue(api.contains("notifications/message"))
        assertTrue(api.contains("progressToken"))

        val evalTool =
            tools.substringAfter("name = \"eval_js\"").substringBefore("name = \"check_source\"")
        assertTrue(evalTool.contains("JsSourceUpsert.validatePayload(js)"))
        assertTrue(
            evalTool
                .compact()
                .contains("Debug.startSimpleDebug(collector,source?.getKey().orEmpty())")
        )
        assertTrue(evalTool.contains("Debug.cancelDebug(collector)"))
        assertTrue(evalTool.contains("withTimeoutOrNull"))
        assertTrue(evalTool.compact().contains("McpSourceScriptEvaluator.evaluate(source,js)"))
        assertFalse(evalTool.contains("runScriptWithContext"))
        assertFalse(evalTool.contains("source.evalJS"))
        val executor =
            projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceScriptEvaluator.kt")
        val compactExecutor = executor.compact()
        assertTrue(compactExecutor.contains("DartSourceEngine::evaluate"))
        assertTrue(compactExecutor.contains("withContext(Dispatchers.IO)"))
        assertTrue(
            compactExecutor.contains("sourceas?BookSource?:source?.getSource()as?BookSource")
        )
        val bookBranch =
            compactExecutor.substringAfter("if(bookSource!=null){").substringBefore("}else{")
        assertTrue(bookBranch.contains("BookSourceScriptBridge.jsonBindings("))
        assertTrue(bookBranch.contains("valvalue=evaluator(bookSource,wrapped,values)"))
        assertTrue(bookBranch.contains("isString->value"))
        assertTrue(bookBranch.contains("else->json.toJson(value)"))
        assertFalse(bookBranch.contains("runScriptWithContext"))
        assertFalse(bookBranch.contains("catch("))
        assertFalse(bookBranch.contains("mainJs"))
        val generalBranch = compactExecutor.substringAfter("}else{")
        assertTrue(generalBranch.contains("V8ScriptExecutor.evaluate(script,values)"))
        // Source-backed RSS calls retain their library, headers and persistent owner;
        // metadata-only auxiliary evaluation cannot replace that source context.
        assertTrue(generalBranch.contains("runInterruptible{"))
        assertTrue(generalBranch.contains("source.evalJS(script){"))
        assertTrue(generalBranch.contains("values.forEach{(key,value)->put(key,value)}"))
        val baseSource =
            projectFile("app/src/main/java/io/legado/app/data/entities/BaseSource.kt").compact()
        assertTrue(baseSource.contains("getSourceNavigationContext()"))
        assertTrue(baseSource.contains("LegacySourceScriptRunner.evaluateBlocking("))
        assertTrue(baseSource.contains("source=this"))
        val sourceRunner =
            projectFile(
                    "app/src/main/java/io/legado/app/model/sourceEngine/LegacySourceScriptRunner.kt"
                )
                .compact()
        assertTrue(sourceRunner.contains("V8ScriptExecutor.evaluateBlocking("))
        assertTrue(sourceRunner.contains("context,source=source"))
        val auxiliary =
            projectFile("app/src/main/java/io/legado/app/model/sourceEngine/DartSourceEngine.kt")
                .substringAfter("object DartSourceEngine")
                .substringAfter("suspend fun evaluateAuxiliary(")
                .substringBefore("suspend fun checkAuxiliarySyntax(")
                .compact()
        assertTrue(auxiliary.contains("valoriginal=source?.let{it.getSource()?:it}"))
        assertTrue(auxiliary.contains("valowner=original?.let(::ownerId)?:sourceId"))
        assertTrue(
            auxiliary.contains(
                "SharedJsScope.resolveLibrary(original?.jsLib,currentCoroutineContext())"
            )
        )
        assertTrue(auxiliary.contains("LegacySourceScriptRunner.prelude(library.orEmpty())"))
        assertTrue(auxiliary.contains("globals[\"__legacySourceTag\"]=it.getTag()"))
        assertTrue(auxiliary.contains("globals[\"__legacySourceKey\"]=it.getKey()"))
        assertTrue(auxiliary.contains("backend.evaluateAuxiliary("))
        assertTrue(auxiliary.contains("ownerPrelude,timeoutMs"))
        val auxiliaryExecutor =
            projectFile("app/src/main/java/io/legado/app/model/sourceEngine/V8ScriptExecutor.kt")
        assertTrue(auxiliaryExecutor.contains("DartSourceEngine.evaluateAuxiliary("))
        assertTrue(generalBranch.contains("JsSourceEngine.normalizeJsResult(raw)"))
        assertFalse(compactExecutor.contains("catch("))
        assertTrue(evalTool.contains("catch (error: CancellationException)"))
        assertFalse(evalTool.contains("debugScope.async"))

        val checkTool = tools.substringAfter("name = \"check_source\"")
        assertTrue(checkTool.contains("Debug.getCheckSnapshot(checkSessionId, urls)"))
        assertTrue(checkTool.contains("Debug.takeCheckSnapshot(checkSessionId, urls)"))
        assertTrue(checkTool.contains("sendCheckProgress("))
        assertTrue(tools.contains("total = total?.toDouble()"))
        assertTrue(tools.contains("logger = \"legado.check_source\""))
        assertFalse(checkTool.contains("getBookSources(urls)"))

        val sourceStore = projectFile("app/src/main/java/io/legado/app/web/mcp/McpSourceStore.kt")
        assertTrue(sourceStore.contains("JsSourceUpsert.prepareForSave"))
        assertTrue(sourceStore.contains("JsSourceUpsert.withSaveLock"))
        assertTrue(sourceStore.contains("mainJs"))
        assertTrue(sourceStore.contains("MAX_SOURCE_BYTES"))
        val upsert = projectFile("app/src/main/java/io/legado/app/model/jsSource/JsSourceUpsert.kt")
        assertTrue(upsert.contains("internal suspend fun <T> withSaveLock"))
    }

    @Test
    fun `tools publish conservative behavior annotations`() {
        val tools = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")
        val registrations = tools.substringAfter("private fun registerTools")
        val profiles =
            mapOf(
                "localReadToolAnnotations" to
                    listOf(
                        "list_sources",
                        "get_source",
                        "get_http_logs",
                        "get_http_log",
                        "get_cookies",
                    ),
                "localWriteToolAnnotations" to
                    listOf(
                        "delete_sources",
                        "set_http_log_recording",
                        "set_cookie",
                        "clear_cookies",
                    ),
                "openWorldWriteToolAnnotations" to
                    listOf(
                        "save_source",
                        "debug_source",
                        "eval_js",
                        "check_source",
                    ),
            )
        val registeredNames =
            Regex("name = \\\"([a-z_]+)\\\"")
                .findAll(registrations)
                .map { it.groupValues[1] }
                .toSet()
        assertEquals(profiles.values.flatten().toSet(), registeredNames)
        profiles.forEach { (profile, names) ->
            names.forEach { name ->
                val tool =
                    registrations
                        .substringAfter("name = \"$name\"")
                        .substringBefore("\n        server.addTool(")
                assertTrue("$name must use $profile", tool.contains("toolAnnotations = $profile"))
            }
        }

        fun hints(profile: String): Set<String> {
            val definition =
                tools
                    .compact()
                    .substringAfter("privateval$profile=ToolAnnotations(")
                    .substringBefore(')')
            return Regex("""([a-zA-Z]+Hint)=(true|false)""")
                .findAll(definition)
                .map { "${it.groupValues[1]} = ${it.groupValues[2]}" }
                .toSet()
        }
        assertEquals(
            setOf("readOnlyHint = true", "openWorldHint = false"),
            hints("localReadToolAnnotations"),
        )
        assertEquals(
            setOf(
                "readOnlyHint = false",
                "destructiveHint = true",
                "idempotentHint = true",
                "openWorldHint = false",
            ),
            hints("localWriteToolAnnotations"),
        )
        assertEquals(
            setOf(
                "readOnlyHint = false",
                "destructiveHint = true",
                "idempotentHint = false",
                "openWorldHint = true",
            ),
            hints("openWorldWriteToolAnnotations"),
        )
    }

    @Test
    fun `request context forwards json source bindings and normalizes results`() = runBlocking {
        val source =
            BookSource(bookSourceUrl = "https://example.com", mainJs = "var mainLoaded = true")
        val objectResult =
            McpSourceScriptEvaluator.evaluate(
                    source,
                    "({message:'ok',items:[1,2]})",
                    mapOf(
                        "request" to mapOf("id" to "abc"),
                        "source" to mapOf("variables" to "invalid"),
                    ),
                ) { forwardedSource, _, bindings ->
                    assertTrue(source === forwardedSource)
                    val dto = bindings["__mcpSource"] as Map<*, *>
                    assertEquals("https://example.com", dto["bookSourceUrl"])
                    assertEquals("var mainLoaded = true", dto["mainJs"])
                    assertEquals(mapOf("id" to "abc"), bindings["request"])
                    assertTrue(bindings.values.none { it is BookSource })
                    assertFalse(bindings.containsKey("source"))
                    assertFalse(dto.containsKey("variables"))
                    mapOf("message" to "ok", "items" to listOf(1, 2))
                }
                .orEmpty()
        assertTrue(objectResult.contains("\"message\":\"ok\""))
        assertTrue(objectResult.contains("\"items\":[1,2]"))
        assertEquals(
            "plain",
            McpSourceScriptEvaluator.evaluate(source, "'plain'") { _, _, _ -> "plain" },
        )
        assertNull(McpSourceScriptEvaluator.evaluate(source, "null") { _, _, _ -> null })
    }

    @Test(timeout = 5_000)
    fun `request cancellation reaches the suspending source executor`() {
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(250) {
                    McpSourceScriptEvaluator.evaluate(BookSource(), "while (true) {}") { _, _, _ ->
                        awaitCancellation()
                    }
                }
            }
        }
    }

    @Test
    fun `server exposes bundled help markdown as read only resources`() {
        val server = projectFile("app/src/main/java/io/legado/app/web/mcp/McpToolServer.kt")

        assertTrue(server.contains("resources = ServerCapabilities.Resources(),"))
        assertTrue(server.contains("val assetDir = \"web/help/md\""))
        assertTrue(server.contains("catch (error: IOException)"))
        assertTrue(server.contains("error.printOnDebug()"))
        assertTrue(server.compact().contains(".filter{it.endsWith(\".md\")}"))
        assertTrue(server.contains(".sorted()"))
        assertTrue(server.contains("val uri = \"legado://help/\$name\""))
        assertTrue(server.contains("server.addResource("))
        assertTrue(server.compact().contains("appCtx.assets.open(\"\$assetDir/\$fileName\")"))
        assertTrue(server.contains("bufferedReader(Charsets.UTF_8)"))
        assertTrue(server.compact().contains(".use{it.readText()}"))
        assertTrue(server.contains("mimeType = \"text/markdown\""))
    }

    @Test
    fun `bundled help resource uris are unique valid and readable`() {
        val files =
            projectPath("app/src/main/assets/web/help/md")
                .listFiles { file -> file.isFile && file.extension == "md" }
                .orEmpty()

        assertTrue(files.isNotEmpty())
        val resources = files.associateWith { file ->
            assertTrue(file.readText(Charsets.UTF_8).isNotBlank())
            URI("legado://help/${file.nameWithoutExtension}")
        }
        val uris = resources.values
        assertEquals(uris.size, uris.distinct().size)
        assertTrue(uris.any { it.toString() == "legado://help/rssRuleHelp" })
        assertTrue(
            resources.all { (file, uri) ->
                uri.scheme == "legado" &&
                    uri.host == "help" &&
                    uri.rawPath == "/${file.nameWithoutExtension}" &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            }
        )
    }

    @Test
    fun `documentation and shrinker keep the security boundary`() {
        val api = projectFile("api.md")
        val updateLog = projectFile("app/src/main/assets/updateLog.md")
        val proguard = projectFile("app/proguard-rules.pro")

        assertTrue(api.contains("X-Legado-Token"))
        assertTrue(api.contains("Host 和 Origin 校验"))
        assertTrue(api.contains("可信局域网"))
        assertTrue(api.contains("令牌等同于书源脚本执行权限"))
        assertTrue(api.contains("eval_js"))
        assertTrue(api.contains("未脱敏 Cookie"))
        assertTrue(api.contains("只合并写入持久层"))
        assertTrue(api.contains("同名会话 Cookie"))
        assertTrue(api.contains("legado://help/"))
        assertTrue(updateLog.contains("**2026/07/22**"))
        assertTrue(updateLog.contains("原生 MCP 书源开发服务"))
        assertTrue(updateLog.contains("支持通过 MCP 在应用内书源环境执行 JavaScript"))
        assertTrue(updateLog.contains("MCP 增加 Cookie 非破坏性读取"))
        assertTrue(updateLog.contains("支持通过 MCP resources 读取应用内帮助文档"))
        assertFalse(proguard.contains("-keep class io.ktor.**"))
        assertFalse(proguard.contains("-keep class kotlinx.coroutines.**"))
    }

    private fun projectFile(path: String): String = projectPath(path).readText()

    private fun projectPath(path: String): File {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        repeat(6) {
            val candidate = File(root, path)
            if (candidate.exists()) return candidate
            root = root.parentFile ?: error("Project root not found for: $path")
        }
        error("Project path not found: $path")
    }

    private fun String.compact(): String = replace(Regex("\\s+"), "")
}

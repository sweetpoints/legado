import de.undercouch.gradle.tasks.download.Download
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    // Cronet 资源下载（原先在 download.gradle + cronet-loader.gradle 里 apply plugin）
    alias(libs.plugins.download)
}

fun releaseTime(): String {
    // 与原先 Groovy 的 new Date().format("yyMMddHH", TimeZone.getTimeZone("GMT+8")) 等价
    val format = SimpleDateFormat("yyMMddHH", Locale.getDefault())
    format.timeZone = TimeZone.getTimeZone("GMT+8")
    return format.format(Date())
}

val appName = "legado"
val appVersion = "3.${releaseTime()}"
var versionCodeValue = 30000
// Preserve the published code at this point, then ignore GitHub wrapper merge commits.
val versionCodeBaseCommit = "9639f027fbc8cfdf57ce136a9e0c98829a235aef"
val versionCodeAtBase = 37540

try {
    val gitCount =
        providers
            .exec {
                commandLine(
                    "git",
                    "rev-list",
                    "$versionCodeBaseCommit..HEAD",
                    "--count",
                    "--no-merges",
                )
                isIgnoreExitValue = true
            }
            .standardOutput
            .asText
            .get()
            .trim()
    if (gitCount.isNotEmpty()) {
        versionCodeValue = versionCodeAtBase + gitCount.toInt()
    }
} catch (ignored: Exception) {
    // Keep source archives buildable when Git is unavailable.
}

val flutterSourceAbis = providers.gradleProperty("flutterSourceAbis")
    .orElse("arm64-v8a,x86_64").get().split(",")
check(flutterSourceAbis.isNotEmpty() && flutterSourceAbis.distinct().size == flutterSourceAbis.size &&
    flutterSourceAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "flutterSourceAbis must contain unique arm64-v8a and/or x86_64."
}
val flutterSourceEngine = true
check(providers.gradleProperty("flutterSourceEngine").orNull != "false") {
    "The Flutter source engine is required. Disabling it cannot restore the removed legacy engine."
}

// AAR preparation is an explicit official Flutter build step. Fail before Maven
// resolution with a useful action instead of silently producing a legacy APK.
val flutterSourceRepository = file(
    providers.gradleProperty("flutterSourceRepository")
        .orElse("$rootDir/flutter/modules/source_host/build/host/outputs/repo").get()
)
val requestedFlutterModes = gradle.startParameter.taskNames.map { it.lowercase() }.let { names ->
    val modes = mutableSetOf<String>()
    if (names.any { "debug" in it }) modes.add("debug")
    if (names.any { "release" in it }) modes.add("release")
    if (modes.isEmpty()) modes.addAll(listOf("debug", "release"))
    modes
}
if (gradle.startParameter.taskNames.any {
    val name = it.lowercase()
    listOf("assemble", "bundle", "test", "lint", "install", "compile", "check").any { part -> part in name }
}) {
    val stampFile = flutterSourceRepository.resolve("source-engine-artifacts.json")
    check(stampFile.isFile) {
        "Flutter source engine AAR assets are not prepared. Run bash flutter/tool/prepare-android-aar.sh " +
            "with the pinned official Android V8 artifact, Flutter 3.47.6 and JDK 21 before this build."
    }
    val stamp = groovy.json.JsonSlurper().parse(stampFile) as Map<*, *>
    val pins = groovy.json.JsonSlurper().parse(file("$rootDir/flutter/tool/v8/pins.json")) as Map<*, *>
    check(stamp["schemaVersion"] == 1 && stamp["v8"] == pins["v8"] && stamp["depotTools"] == pins["depotTools"]) {
        "Flutter AAR provenance does not match pinned V8. Run bash flutter/tool/prepare-android-aar.sh."
    }
    val bridgeDigest = MessageDigest.getInstance("SHA-256")
    val bridgeFiles = mapOf(
        "src/source_v8.cpp" to file("$rootDir/flutter/packages/source_v8/src/source_v8.cpp"),
        "src/source_v8.h" to file("$rootDir/flutter/packages/source_v8/src/source_v8.h"),
        "src/android_exports.map" to file("$rootDir/flutter/packages/source_v8/src/android_exports.map"),
        "tool/v8/source_v8.gni" to file("$rootDir/flutter/tool/v8/source_v8.gni"),
        "tool/v8/link_sdk.py" to file("$rootDir/flutter/tool/v8/link_sdk.py"),
        "tool/v8/toolchain-pins.json" to file("$rootDir/flutter/tool/v8/toolchain-pins.json")
    )
    for ((label, sourceFile) in bridgeFiles.toSortedMap()) {
        val name = label.toByteArray(Charsets.UTF_8)
        val bytes = sourceFile.readBytes()
        bridgeDigest.update(ByteBuffer.allocate(8).putLong(name.size.toLong()).array())
        bridgeDigest.update(name)
        bridgeDigest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        bridgeDigest.update(bytes)
    }
    val bridgeHash = bridgeDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val bridge = stamp["bridge"] as? Map<*, *>
    check(bridge?.get("abi") == 1 && bridge?.get("sourceSha256") == bridgeHash) {
        "Flutter AAR native bridge inputs changed. Relink the bridge against the pinned prebuilt V8 SDK and prepare the Flutter AAR repository."
    }
    val workspace = file("$rootDir/flutter")
    val sourceFiles = mutableListOf<java.io.File>()
    sourceFiles += workspace.resolve("modules/source_host/lib").walkTopDown().filter { it.isFile && it.extension == "dart" }.toList()
    workspace.resolve("packages").listFiles()?.filter { it.isDirectory }?.forEach { packageDir ->
        sourceFiles += packageDir.resolve("lib").walkTopDown().filter { it.isFile && it.extension == "dart" }.toList()
        sourceFiles += packageDir.resolve("pubspec.yaml")
    }
    sourceFiles += listOf(workspace.resolve("pubspec.yaml"), workspace.resolve("pubspec.lock"),
        workspace.resolve("modules/source_host/pubspec.yaml"))
    val sourceDigest = MessageDigest.getInstance("SHA-256")
    for (sourceFile in sourceFiles.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }) {
        val label = sourceFile.relativeTo(rootDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)
        val bytes = sourceFile.readBytes()
        sourceDigest.update(ByteBuffer.allocate(8).putLong(label.size.toLong()).array())
        sourceDigest.update(label)
        sourceDigest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        sourceDigest.update(bytes)
    }
    val sourceHash = sourceDigest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    check(stamp["sourceSha256"] == sourceHash) {
        "Flutter AAR source inputs changed. Run bash flutter/tool/prepare-android-aar.sh before building."
    }
    val artifacts = stamp["artifacts"] as? Map<*, *> ?: emptyMap<Any, Any>()
    for (mode in requestedFlutterModes) {
        val entry = artifacts[mode] as? Map<*, *>
        check(entry != null) { "Flutter $mode AAR is missing. Run bash flutter/tool/prepare-android-aar.sh." }
        val aar = flutterSourceRepository.resolve(
            "io/legado/source/source_host/flutter_$mode/1.0/flutter_$mode-1.0.aar"
        )
        check(aar.isFile && aar.resolveSibling("flutter_$mode-1.0.pom").isFile) {
            "Flutter $mode AAR/POM assets are missing. Run bash flutter/tool/prepare-android-aar.sh."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(aar.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(digest == entry["sha256"]) {
            "Flutter $mode AAR checksum differs from preparation. Run bash flutter/tool/prepare-android-aar.sh."
        }
        ZipFile(aar).use { archive ->
            check(flutterSourceAbis.all { archive.getEntry("jni/$it/libsource_v8.so") != null } &&
                archive.getEntry("assets/flutter_assets/NativeAssetsManifest.json") != null) {
                "Flutter $mode AAR lacks native V8/assets. Run bash flutter/tool/prepare-android-aar.sh."
            }
        }
    }
}

// The isolated instrumentation application has no Firebase client registration.
if (
    flutterSourceEngine &&
        providers.gradleProperty("flutterSourceTestSuffix").orNull == ".fluttertest"
) {
    tasks.matching { it.name == "processAppDebugGoogleServices" }.configureEach { enabled = false }
}

val armOnly = (project.findProperty("armOnly") as String?)?.toBoolean() ?: false

// ---------------------------------------------------------------------------
// Cronet：资源下载（原 download.gradle）
// 版本号来自根 gradle.properties 的 CronetVersion / CronetMainVersion
// ---------------------------------------------------------------------------
val cronetVersion = providers.gradleProperty("CronetVersion").get()
val cronetMainVersion = providers.gradleProperty("CronetMainVersion").get()
val cronetBasePath =
    "https://storage.googleapis.com/chromium-cronet/android/$cronetVersion/Release/cronet/"
val cronetAssetsDir = "$projectDir/src/main/assets"
val cronetLibPath = "$projectDir/cronetlib"
val cronetSoPath = "$projectDir/so"
// cronet_impl_util_java.jar is already merged into impl-common and would duplicate classes.
val cronetJars =
    listOf(
        "cronet_api.jar",
        "cronet_impl_common_java.jar",
        "cronet_impl_native_java.jar",
        "cronet_impl_native_sentinel_java.jar",
        "cronet_impl_platform_java.jar",
        "cronet_shared_java.jar",
        "httpengine_native_provider_java.jar",
    )
val cronetAbis = listOf("armeabi-v7a", "arm64-v8a", "riscv64", "x86", "x86_64")

/** 从文件生成 MD5 */
fun generateMD5(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024)
        var numRead = input.read(buffer)
        while (numRead > 0) {
            digest.update(buffer, 0, numRead)
            numRead = input.read(buffer)
        }
    }
    return String.format("%032x", BigInteger(1, digest.digest())).lowercase()
}

/** 下载 Cronet 相关的 jar */
tasks.register<Download>("downloadJar") {
    src(cronetJars.map { cronetBasePath + it })
    dest(cronetLibPath)
    overwrite(true)
    onlyIfModified(false)

    doLast {
        project
            .fileTree(cronetLibPath) {
                include("*.jar", "*.aar")
            }
            .files
            .filter { it.name !in cronetJars }
            .forEach {
                project.delete(it)
            }
    }
}

/** 下载 Cronet 各 ABI 的 so */
tasks.register<Download>("downloadARM64") {
    src(cronetBasePath + "libs/arm64-v8a/libcronet.$cronetVersion.so")
    dest("$cronetSoPath/arm64-v8a.so")
    overwrite(true)
    onlyIfModified(true)
}

tasks.register<Download>("downloadARMv7") {
    src(cronetBasePath + "libs/armeabi-v7a/libcronet.$cronetVersion.so")
    dest("$cronetSoPath/armeabi-v7a.so")
    overwrite(true)
    onlyIfModified(true)
}

tasks.register<Download>("downloadRISCV64") {
    src(cronetBasePath + "libs/riscv64/libcronet.$cronetVersion.so")
    dest("$cronetSoPath/riscv64.so")
    overwrite(true)
    onlyIfModified(true)
}

tasks.register<Download>("downloadX86_64") {
    src(cronetBasePath + "libs/x86_64/libcronet.$cronetVersion.so")
    dest("$cronetSoPath/x86_64.so")
    overwrite(true)
    onlyIfModified(true)
}

tasks.register<Download>("downloadX86") {
    src(cronetBasePath + "libs/x86/libcronet.$cronetVersion.so")
    dest("$cronetSoPath/x86.so")
    overwrite(true)
    onlyIfModified(true)
}

/** 更新 Cronet 版本时执行这个 task 先更改 gradle.properties 里面的版本号，然后再执行 gradlew app:downloadCronet */
tasks.register("downloadCronet") {
    dependsOn(
        "downloadJar",
        "downloadARM64",
        "downloadARMv7",
        "downloadRISCV64",
        "downloadX86_64",
        "downloadX86",
    )

    doLast {
        val hashes = LinkedHashMap<String, String>()
        cronetAbis.forEach { abi ->
            // 用 ${abi} 显式界定变量边界：虽然 Kotlin 的 "$abi.so" 不会像 Groovy 那样
            // 把 .so 当成属性访问，但显式写法更不容易误读，也便于 CronetDownloadTaskTest 校验。
            val file = File(cronetSoPath, "${abi}.so")
            if (!file.isFile) {
                throw GradleException("Missing Cronet library for $abi: $file")
            }
            println(abi)
            hashes[abi] = generateMD5(file)
        }
        hashes["version"] = cronetVersion
        // 原先用 groovy.json.JsonOutput.toJson；这里是等价的扁平字符串 map 输出
        val metadata =
            hashes.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "\"$k\":\"$v\""
            }
        println(metadata)

        println(cronetAssetsDir)
        val f1 = File("$cronetAssetsDir/cronet.json")
        f1.parentFile.mkdirs()
        f1.writeText(metadata, Charsets.UTF_8)
    }
}

// ---------------------------------------------------------------------------
// Cronet：动态加载器改写（原 cronet-loader.gradle）
// Version-pinned adaptation. Keep the checked-in official JAR byte-for-byte intact.
// Cronet 153's public setLibraryLoader() is a no-op; reconnect its actual load site instead.
// ---------------------------------------------------------------------------
val cronetOriginalJar = file("cronetlib/cronet_impl_native_java.jar")
val cronetAdaptedJar = layout.buildDirectory.file("cronet-dynamic/cronet_impl_native_java.jar")

val adaptCronetLoader =
    tasks.register("adaptCronetLoader") {
        inputs.file(cronetOriginalJar)
        inputs.file("build.gradle.kts")
        inputs.property("version", cronetVersion)
        outputs.file(cronetAdaptedJar)

        doLast {
            val sha256 =
                MessageDigest.getInstance("SHA-256")
                    .digest(cronetOriginalJar.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            // 注意：这里原本是 Groovy 的 assert。Kotlin 的 assert 默认不启用，必须用 check()。
            check(
                cronetVersion == "153.0.8010.27" &&
                    sha256 == "775d145e5f33fd6157078a574f788b850bda5a0e7195aa9ec93e67afe6f64127"
            ) {
                "Review the dynamic loader against the new official Cronet JAR first"
            }
            val owner = "org/chromium/net/impl/CronetLibraryLoader"
            val destination = cronetAdaptedJar.get().asFile
            destination.parentFile.mkdirs()
            ZipFile(cronetOriginalJar).use { source ->
                JarOutputStream(destination.outputStream()).use { output ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        var bytes: ByteArray = source.getInputStream(entry).use { it.readBytes() }
                        if (entry.name == "$owner.class") {
                            val node = ClassNode()
                            val reader = ClassReader(bytes)
                            reader.accept(node, 0)
                            val method =
                                node.methods.find {
                                    it.name == "loadLibrary" && it.desc == "()V"
                                } ?: error("loadLibrary()V not found in $owner")
                            val instructions = method.instructions.toArray()
                            val calls = instructions.filter {
                                it is MethodInsnNode &&
                                    it.owner == "java/lang/System" &&
                                    it.name == "loadLibrary"
                            }
                            val flag = instructions.filter {
                                it is FieldInsnNode &&
                                    it.opcode == Opcodes.PUTSTATIC &&
                                    it.name == "sLibAlreadyLoaded"
                            }
                            check(calls.size == 3 && flag.size == 1) {
                                "Unexpected Cronet loader shape: calls=${calls.size} flag=${flag.size}"
                            }
                            check(flag[0].previous.opcode == Opcodes.ICONST_1) {
                                "Unexpected sLibAlreadyLoaded flag sequence"
                            }
                            // Keep the AOSP branch unchanged; redirect only the two standalone
                            // Cronet names.
                            calls.drop(1).forEach { call ->
                                val route = InsnList()
                                route.add(
                                    FieldInsnNode(
                                        Opcodes.GETSTATIC,
                                        "io/legado/app/lib/cronet/CronetLoader",
                                        "INSTANCE",
                                        "Lio/legado/app/lib/cronet/CronetLoader;",
                                    )
                                )
                                route.add(InsnNode(Opcodes.SWAP))
                                route.add(
                                    MethodInsnNode(
                                        Opcodes.INVOKEVIRTUAL,
                                        "io/legado/app/lib/cronet/CronetLoader",
                                        "loadLibrary",
                                        "(Ljava/lang/String;)V",
                                        false,
                                    )
                                )
                                method.instructions.insertBefore(call, route)
                                method.instructions.remove(call)
                            }
                            // A failed download/load must not tell normal initialization that
                            // loading succeeded.
                            method.instructions.remove(flag[0].previous)
                            method.instructions.remove(flag[0])
                            val returns = instructions.filter { it.opcode == Opcodes.RETURN }
                            check(returns.size == 1) { "Unexpected RETURN count: ${returns.size}" }
                            val loaded = InsnList()
                            loaded.add(InsnNode(Opcodes.ICONST_1))
                            loaded.add(flag[0])
                            method.instructions.insertBefore(returns[0], loaded)
                            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
                            node.accept(writer)
                            bytes = writer.toByteArray()
                        }
                        val target = ZipEntry(entry.name)
                        target.time = 0
                        output.putNextEntry(target)
                        output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
            logger.lifecycle(
                "Cronet 153: adapted only Java library loading; official native/JNI initialization unchanged"
            )
        }
    }

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    namespace = "io.legado.app"
    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    signingConfigs {
        if (project.hasProperty("RELEASE_STORE_FILE")) {
            create("myConfig") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }
    defaultConfig {
        applicationId = "com.legado.app"
        // V8 native assets and the Flutter source host require Android API 26.
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FLUTTER_SOURCE_ENGINE", flutterSourceEngine.toString())
        // Keep app translations while dropping unused transitive dependency locales.
        resourceConfigurations.addAll(
            listOf(
                "en",
                "es-rES",
                "ja-rJP",
                "pt-rBR",
                "vi",
                "zh",
                // AndroidX stores Simplified Chinese switch labels under zh-rCN.
                "zh-rCN",
                "zh-rHK",
                "zh-rTW",
            )
        )
        extensions.extraProperties.set("archivesBaseName", "${appName}_$appVersion")

        // Package only ABIs backed by the prepared Flutter/V8 AAR.
        // Keep the enabled APK from advertising ABIs without Flutter/V8.
        if (flutterSourceEngine) {
            ndk { abiFilters.addAll(flutterSourceAbis) }
        } else if (armOnly) {
            ndk {
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
        }

        buildConfigField("String", "Cronet_Version", "\"$cronetVersion\"")
        buildConfigField("String", "Cronet_Main_Version", "\"$cronetMainVersion\"")

        javaCompileOptions {
            annotationProcessorOptions {
                arguments.putAll(
                    mapOf(
                        "room.incremental" to "true",
                        "room.expandProjection" to "true",
                        "room.schemaLocation" to "$projectDir/schemas",
                    )
                )
            }
        }
    }
    if (flutterSourceEngine) {
        sourceSets.getByName("main").kotlin.directories.add("src/flutterSource/java")
    }
    buildFeatures {
        buildConfig = true
        viewBinding = false
        compose = true
    }
    testOptions {
        unitTests {
            // 本项目的单元测试是纯 JVM 测试、未使用 Robolectric，但部分被测代码会经由
            // Debug.log 间接走到 android.util.Log，默认会抛 "Method d in android.util.Log not mocked"。
            // 让未实现的 framework 方法返回默认值即可；否则每次跑全量单测都会有 9 个固定失败，
            // 无法从中分辨新引入的回归。
            // 注意：Groovy DSL 下属性名是 returnDefaultValues，Kotlin DSL 是 isReturnDefaultValues。
            isReturnDefaultValues = true
        }
    }
    buildTypes {
        release {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("myConfig")
            }
            applicationIdSuffix = ".release"
            if (applicationIdSuffix == ".releaseA") {
                manifestPlaceholders["app_name"] = "@string/app_name_a"
            } else {
                manifestPlaceholders["app_name"] = "@string/app_name"
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "cronet-proguard-rules.pro",
            )
        }
        debug {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("myConfig")
            }
            manifestPlaceholders["app_name"] = "@string/app_name"

            applicationIdSuffix =
                if (flutterSourceEngine)
                    providers.gradleProperty("flutterSourceTestSuffix").orElse(".debug").get()
                else ".debug"
            versionNameSuffix = "debug"
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "cronet-proguard-rules.pro",
            )
        }
    }
    flavorDimensions += "mode"
    productFlavors {
        create("app") {
            dimension = "mode"
            manifestPlaceholders["APP_CHANNEL_VALUE"] = "app"
        }
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }
    // 设定Room的KSP参数
    ksp {
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
        arg("room.generateKotlin", "false")
    }

    compileOptions {
        // Flag to enable support for the new language APIs
        isCoreLibraryDesugaringEnabled = true
        // Java 字节码目标（Android 侧需要，与运行 Gradle 的 JDK 版本无关）
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources.excludes.add("META-INF/*")
        // R8 removes the transcode implementation; these bundled resources have no runtime reader.
        resources.excludes.addAll(
            listOf(
                "tables/Transcoder_*.bin",
                "*.proto",
                "**/*.proto",
                "src/**",
                // kotlin-reflect and its builtins loaders are not part of the release runtime.
                "kotlin/*.kotlin_builtins",
                "kotlin/**/*.kotlin_builtins",
            )
        )
        jniLibs {
            // These dependencies already ship stripped; skip redundant strip attempts.
            keepDebugSymbols.addAll(
                listOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libarchive-jni.so",
                    "**/libdatastore_shared_counter.so",
                    "**/libimage_processing_util_jni.so",
                    "**/librenderscript-toolkit.so",
                    "**/librtmp-jni.so",
                    "**/libsurface_util_jni.so",
                )
            )
        }
    }

    sourceSets {
        // Adds exported schema location as test app assets.
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }
    lint {
        checkDependencies = true
        // 忽略string翻译缺失，后面需要翻译时去掉，todo
        disable += "MissingTranslation"
    }
}

dependencies {
    if (flutterSourceEngine) {
        debugImplementation("io.legado.source.source_host:flutter_debug:1.0")
        releaseImplementation("io.legado.source.source_host:flutter_release:1.0")
    }
    coreLibraryDesugaring(libs.desugar)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.bundles.androidTest)
    // kotlin
    implementation(libs.kotlin.stdlib)
    // Kotlin反射（刻意排除，见 app/proguard-rules.pro 中的说明）
    // implementation(libs.kotlin.reflect)

    // 协程
    implementation(libs.bundles.coroutines)

    // 图像处理库Toolkit
    implementation(libs.renderscript.intrinsics.replacement.toolkit)

    // androidX
    implementation(libs.core.ktx)
    implementation(libs.appcompat.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)

    // Compose UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    // LiveData 状态供 Compose 页面收集。
    implementation(libs.compose.runtime.livedata)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)

    // google
    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.gson)

    // lifecycle
    implementation(libs.lifecycle.common.java8)
    implementation(libs.lifecycle.service)

    // media
    implementation(libs.media.media)
    // For media playback using ExoPlayer
    implementation(libs.media3.exoplayer)
    // For loading data using the OkHttp network stack
    implementation(libs.media3.datasource.okhttp)

    // videoPlayer
    implementation(libs.gsyVideoPlayer.java)
    implementation(libs.gsyVideoPlayer.exo2) {
        exclude(group = "androidx.media3", module = "media3-cast")
    }
    // 弹幕
    implementation(libs.danmakuFlameMaster)

    // Splitties
    implementation(libs.splitties.appctx)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views)

    // room sql语句不高亮解决方法https://issuetracker.google.com/issues/234612964#comment6
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    androidTestImplementation(libs.room.testing)

    // liveEventBus
    implementation(libs.liveeventbus)

    // 规则相关
    implementation(libs.jsoup)
    implementation(libs.json.path)
    implementation(libs.jsoupxpath)
    implementation(project(":modules:book"))

    // 网络
    implementation(libs.okhttp)
    implementation(libs.brotli.dec)
    implementation(
        fileTree("cronetlib") {
            include("*.jar", "*.aar")
            exclude("cronet_impl_native_java.jar")
        }
    )
    // 经 ASM 改写后的 cronet jar（由上面的 adaptCronetLoader 任务产出）
    implementation(files(cronetAdaptedJar).builtBy(adaptCronetLoader))

    implementation(libs.protobuf.javalite)

    // Glide
    implementation(libs.glide.glide)
    implementation(libs.glide.okhttp)
    ksp(libs.glide.ksp)

    // Svg
    implementation(libs.androidsvg)
    // Glide svg plugin
    implementation(libs.glide.svg)

    // webServer
    implementation(libs.nanohttpd.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    // MCP server (Streamable HTTP)
    implementation(libs.mcp.sdk.server) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    implementation(libs.ktor.server.cio) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    testImplementation(libs.ktor.server.test.host) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

    // 二维码
    implementation(libs.zxing.lite)

    // 颜色选择
    implementation(libs.colorpicker)

    // 压缩解压
    implementation(libs.libarchive)

    // apache
    implementation(libs.commons.text)

    // MarkDown
    implementation(libs.markwon.core)
    implementation(libs.markwon.image.glide)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)

    // 转换繁体
    implementation(libs.quick.chinese.transfer.core)

    // 加解密类库,有些书源使用
    implementation(libs.hutool.crypto)
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.pdfbox.android) {
        // PDFBox's older jdk15to18 artifacts duplicate the project's jdk18on classes.
        exclude(group = "org.bouncycastle")
    }

    // firebase, 崩溃统计和性能统计
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.perf)

    implementation(libs.glide.recyclerview)

    // 椒盐歌词
    implementation(libs.lyricViewx)
    // sora-editor代码编辑器,更丰富的编辑功能
    implementation(platform(libs.soraEditor.bom))
    implementation(libs.soraEditor.core)
    implementation(libs.soraEditor.language.textmate)
}

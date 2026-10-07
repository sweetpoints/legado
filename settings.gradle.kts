import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    // 这里曾钉过 com.android.tools:r8:9.1.29 与 r8-releases 仓库，用来满足
    // 「Kotlin 2.4 metadata requires R8 9.1.29 or newer」。AGP 9 自带的 R8 已满足该要求，
    // 继续钉反而是一次降级（并触发 "project includes version X of R8, while AGP was shipped with Y" 警告），
    // 因此改为直接使用 AGP 自带的 R8。
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        //原仓库
        gradlePluginPortal()
        mavenCentral()
        //镜像仓库,无法连接源仓库自行启用镜像仓库,不要提交修改
        //maven("https://maven-central-asia.storage-download.googleapis.com/maven2/")
        //maven("https://maven.aliyun.com/repository/google")
        //maven("https://maven.aliyun.com/repository/public")
        //maven("https://maven.aliyun.com/repository/gradle-plugin")
        //maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

plugins {
    // 允许 Gradle 自动下载 toolchain（本项目需要 JDK 21，见 app/build.gradle 的 jvmToolchain）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        //原仓库
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex("com\\.github.*")
            }
        }
        mavenCentral()
        run {
            maven {
                url =
                    uri(
                        providers
                            .gradleProperty("flutterSourceRepository")
                            .orElse("$rootDir/flutter/modules/source_host/build/host/outputs/repo")
                            .get()
                    )
            }
            maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        }

        //镜像仓库,无法连接源仓库自行启用镜像仓库,不要提交修改
        //maven("https://maven-central-asia.storage-download.googleapis.com/maven2/")
        //maven("https://maven.aliyun.com/repository/google")
        //maven("https://maven.aliyun.com/repository/public")
        //maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "legado"

include(":app")
include(":modules:book")

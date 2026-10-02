plugins {
    alias(libs.plugins.android.library)
    // AGP 9 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    namespace = "me.ag2s"
    kotlin {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    defaultConfig {
        minSdk = 21
        // 库模块不再声明 targetSdk：AGP 9 的 Kotlin DSL 已不暴露该属性（最终由 app 模块决定）

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        checkDependencies = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val includeEmulatorAbi = providers.gradleProperty("includeEmulatorAbi").orNull == "true"

android {
    namespace = "com.moatazvid.speech.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-O3") } }
        ndk {
            abiFilters += if (includeEmulatorAbi) {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    implementation(project(":speech-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.work.runtime)
}

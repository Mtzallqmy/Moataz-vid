plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.moatazvid.speech.android"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-O3") } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    implementation(project(":speech-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.work.runtime)
}

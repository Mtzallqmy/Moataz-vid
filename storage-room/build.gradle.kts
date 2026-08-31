plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.moatazvid.storage.room"
    compileSdk = 36

    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":storage-core"))
    implementation(libs.room3.runtime)
    implementation(libs.kotlinx.coroutines.core)
    ksp(libs.room3.compiler)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.room3.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}


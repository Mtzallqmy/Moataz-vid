plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val includeEmulatorAbi = providers.gradleProperty("includeEmulatorAbi").orNull == "true"

android {
    namespace = "com.moatazvid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moatazvid.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += if (includeEmulatorAbi) {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    testOptions {
        animationsDisabled = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":storage-core"))
    implementation(project(":storage-room"))
    implementation(project(":media-engine"))
    implementation(project(":media3-adapter"))
    implementation(project(":speech-core"))
    implementation(project(":speech-android"))
    implementation(project(":ai-provider-core"))
    implementation(project(":ai-provider-android"))
    implementation(project(":ai-editor-core"))
    implementation(project(":editor-core"))
    implementation(project(":editor-ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room3.runtime)
    implementation(libs.work.runtime)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

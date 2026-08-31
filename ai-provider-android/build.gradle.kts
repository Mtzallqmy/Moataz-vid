plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.moatazvid.ai.provider.android"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    api(project(":ai-provider-core"))
    implementation(project(":storage-room"))
    implementation(libs.room3.runtime)
    implementation(libs.kotlinx.coroutines.core)
}

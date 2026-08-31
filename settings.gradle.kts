pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MoatazVid"

include(":core-model")
include(":storage-core")
include(":media-engine")
include(":speech-core")
include(":ai-provider-core")
include(":ai-editor-core")

// Android adapters are real source modules, but are opt-in so the pure core can be
// built on hosts that do not have an Android SDK (documentation/CI lint workers).
if (providers.gradleProperty("includeAndroidModules").orNull == "true") {
    include(":storage-room")
    include(":media3-adapter")
    include(":speech-android")
    include(":ai-provider-android")
}

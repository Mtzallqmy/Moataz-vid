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
include(":speech-core")
include(":video-use-core")
include(":media-engine")
include(":ai-provider-core")
include(":ai-editor-core")
include(":editor-core")

// Android modules are opt-in so pure core builds remain available on hosts without an Android SDK.
if (providers.gradleProperty("includeAndroidModules").orNull == "true") {
    include(":storage-room")
    include(":media3-adapter")
    include(":speech-android")
    include(":ai-provider-android")
    include(":editor-ui")
    include(":app")
}

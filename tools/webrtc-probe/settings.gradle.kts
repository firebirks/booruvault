// A build of its own, run from the repository's root with ./gradlew -p tools/webrtc-probe: it is
// no part of the app, and F-Droid's build of the app never sees it.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The app's own versions, so the probe builds with the same plugin and WebView library.
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "webrtc-probe"
include(":app")

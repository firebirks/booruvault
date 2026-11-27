// A build of its own, run from the repository's root with ./gradlew -p tools/gecko-probe: a
// prototype of the built-in browsers on GeckoView, no part of the app, and F-Droid's build of the
// app never sees it. GeckoView comes from Mozilla's Maven repository, which only this build uses.
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
        exclusiveContent {
            forRepository { maven("https://maven.mozilla.org/maven2/") }
            filter { includeGroup("org.mozilla.geckoview") }
        }
    }
    // The app's own versions, for the plugin, Kotlin and OkHttp.
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "gecko-probe"
include(":app")

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
        // GeckoView, the engine of the built-in browsers, from Mozilla's repository and from no
        // other, and nothing else from there: a repository asked first could otherwise serve it.
        exclusiveContent {
            forRepository { maven("https://maven.mozilla.org/maven2/") }
            filter { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "BooruVault"
include(":app")

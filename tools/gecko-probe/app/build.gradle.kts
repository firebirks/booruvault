plugins {
    alias(libs.plugins.android.application)
}

/**
 * Takes the engine's preferences out of the app's own source, so that the probe always checks what
 * the app ships and never a copy of it.
 */
abstract class TakeSources : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun take() {
        val into = output.get().dir("io/github/firebirks/booruvault/net").asFile.apply { mkdirs() }
        sources.forEach { it.copyTo(File(into, it.name), overwrite = true) }
    }
}

val appNet = rootProject.layout.projectDirectory.dir("../../app/src/main/kotlin/io/github/firebirks/booruvault/net")
val takeEngineSources = tasks.register<TakeSources>("takeEngineSources") {
    sources.from(appNet.file("GeckoPrefs.kt"), appNet.file("Route.kt"), appNet.file("SocksCredential.kt"))
}

android {
    namespace = "io.github.firebirks.booruvault.probe.gecko"
    // GeckoView 161 asks to be compiled against 37.2.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.firebirks.booruvault.probe.gecko"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        // GeckoView's native libraries are about 100 MB for each ABI: the phones in use only.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

androidComponents {
    onVariants { variant ->
        // The app's own extension, and its preferences, as the app has them.
        variant.sources.assets?.addStaticSourceDirectory(
            rootProject.layout.projectDirectory.dir("../../app/src/main/assets").asFile.path,
        )
        variant.sources.java?.addGeneratedSourceDirectory(takeEngineSources, TakeSources::output)
    }
}

dependencies {
    // The app's own version: the prototype tries the engine the app ships.
    implementation(libs.geckoview) {
        // Google Play services, for WebAuthn: the app has no dependency on them.
        exclude(group = "com.google.android.gms")
    }
    // For the Cloudflare check: the app's own HTTP client sends on what the browser earned.
    implementation(libs.okhttp)
}

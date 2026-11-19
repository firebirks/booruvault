plugins {
    alias(libs.plugins.android.application)
}

/**
 * Takes the script the app's built-in browsers run, DISABLE_WEBRTC_SCRIPT, out of the app's own
 * source, so that the probe always checks the script the app ships and never a copy of it.
 */
abstract class ExtractScript : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun extract() {
        val text = source.get().asFile.readText()
        val script = Regex("const val DISABLE_WEBRTC_SCRIPT = \"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
            ?: throw GradleException("DISABLE_WEBRTC_SCRIPT is not in ${source.get().asFile}")
        output.get().file("disable_webrtc.js").asFile.apply {
            parentFile.mkdirs()
            writeText(script)
        }
    }
}

val extractScript = tasks.register<ExtractScript>("extractWebRtcScript") {
    source.set(rootProject.layout.projectDirectory.file("../../app/src/main/kotlin/io/github/firebirks/booruvault/net/PrivateWebView.kt"))
}

android {
    namespace = "io.github.firebirks.booruvault.probe.webrtc"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.firebirks.booruvault.probe.webrtc"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(extractScript, ExtractScript::output)
    }
}

dependencies {
    implementation(libs.webkit)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from keystore.properties next to the project, never committed. Without
// it the release build is unsigned, which is how F-Droid builds it before signing it itself.
val keystoreProperties: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().also { props -> file.inputStream().use(props::load) }
}

android {
    namespace = "io.github.firebirks.booruvault"
    // GeckoView asks to be compiled against 37.2.
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.firebirks.booruvault"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.7.0"
    }

    signingConfigs {
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Beside the release on the same phone, with a vault of its own: a debug build signed with
        // another key could not update the release, and replacing it would take the vault with it.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // The release build as R8 leaves it, for trying on a phone what only a shrunk build can
        // get wrong: beside the release and the debug build, with a vault of its own, signed with
        // the debug key. Not debuggable: R8 renames nothing in a debuggable build, and what it
        // renames is what a release can get wrong. Its files are out of adb's reach, as a
        // release's are; the debug build is the one to read them in.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Lists the translated languages for Android 13+'s per-app language setting.
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // GeckoView's native code is about 100 MB for each ABI: one APK for each.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Compressed in the APK, which halves the download; Android unpacks them at install.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }
}

// An APK for each ABI needs a version code for each: F-Droid tells the APKs of one version apart
// by it. The code in defaultConfig is the release's own, raised by one each time; an APK's is
// that times ten plus its ABI's digit, in the order F-Droid's own builds use.
val abiDigits = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 4)
androidComponents {
    onVariants { variant ->
        val release = android.defaultConfig.versionCode ?: 0
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            abiDigits[abi]?.let { output.versionCode.set(release * 10 + it) }
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.fragment.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.biometric)
    implementation(libs.documentfile)
    implementation(libs.geckoview) {
        // Google Play services, for WebAuthn: the app has no dependency on them.
        exclude(group = "com.google.android.gms")
    }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable.image.coil3)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.snakeyaml)
}

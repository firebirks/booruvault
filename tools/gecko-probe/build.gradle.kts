// The serialization plugin is not applied: it puts the app's Kotlin version on the build
// classpath, which AGP's built-in Kotlin then uses, as it does for the app. GeckoView's own
// Kotlin standard library is too new for the compiler AGP would otherwise bring.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

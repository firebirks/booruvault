// AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin is declared.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

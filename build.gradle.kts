/**
 * Root-level build.gradle.kts - Declares Gradle plugins used across all modules.
 *
 * This is the **project-level** build file. It does NOT apply plugins directly -- it
 * only declares them with `apply false` so that individual modules (like :app) can
 * apply them selectively. This is the standard pattern for multi-module Android projects.
 *
 * Plugin catalog:
 * - **android.application**: The Android application plugin, applied in :app to build the APK.
 * - **kotlin.android**: Kotlin support for Android (Kotlin/JVM with Android extensions).
 * - **kotlin.compose**: Enables the Kotlin Compose compiler plugin (required for @Composable).
 * - **ksp**: Kotlin Symbol Processing -- used by Room and Hilt for compile-time code generation.
 *   KSP is the successor to kapt and is significantly faster.
 * - **hilt**: Dagger Hilt plugin -- sets up annotation processing for dependency injection.
 * - **kotlin.serialization**: Enables kotlinx.serialization compiler plugin, used here for
 *   type-safe Navigation Compose routes (@Serializable data objects).
 *
 * All plugin versions are defined in `gradle/libs.versions.toml` (the version catalog)
 * and referenced here via `libs.plugins.*` aliases.
 *
 * @see app/build.gradle.kts for where these plugins are actually applied
 * @see gradle/libs.versions.toml for plugin version definitions
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

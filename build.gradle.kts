// Root build file: declares plugins (applied in :app) and pins the Kotlin Gradle plugin.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 9 built-in Kotlin ships with an older KGP; this lifts it to the catalog's Kotlin version
        // (https://developer.android.com/build/releases/agp-9-0-0-release-notes).
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

/**
 * settings.gradle.kts - Configures repository sources and includes project modules.
 *
 * This file runs before any build.gradle.kts files. It:
 * 1. Configures where Gradle resolves plugins from (pluginManagement).
 * 2. Configures where Gradle resolves dependencies from (dependencyResolutionManagement).
 * 3. Sets the project name and includes the :app module.
 *
 * Repository hierarchy:
 * - **google()**: Google's Maven repository -- hosts Android SDK libraries, Compose,
 *   Hilt, WorkManager, Room, Play Services, etc.
 * - **mavenCentral()**: The largest public Maven repository -- hosts most open-source
 *   Java/Kotlin libraries (Kotlin stdlib, Coroutines, Jackson, etc.).
 * - **gradlePluginPortal()**: Hosts Gradle plugins (only used for plugin resolution,
 *   not for regular dependencies).
 *
 * Key settings:
 * - **includeGroupByRegex**: In pluginManagement, restricts the google() repository
 *   to only com.android.*, com.google.*, and androidx.* groups. This speeds up
 *   resolution by avoiding unnecessary lookups.
 * - **FAIL_ON_PROJECT_REPOS**: Ensures all repositories are declared here centrally,
 *   not in individual module build files. This enforces consistent dependency resolution.
 * - **rootProject.name**: The display name of the root project ("SignalBackup").
 * - **include(":app")**: Includes the single application module.
 *
 * Note: The @Suppress("UnstableApiUsage") is needed because
 * dependencyResolutionManagement is still marked as incubating in some Gradle versions.
 */
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SignalBackup"
include(":app")

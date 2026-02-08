/**
 * app/build.gradle.kts - Build configuration for the :app module.
 *
 * This file configures everything needed to compile, package, and run the Signal Backup
 * Android application. It is organized into sections:
 *
 * 1. **Plugins**: Applied from the root project's declarations.
 * 2. **Android block**: SDK versions, signing, build types, Java/Kotlin, Compose, packaging.
 * 3. **Dependencies**: All libraries the app uses, organized by category.
 *
 * Signing configuration:
 * The release signing config reads keystore credentials from environment variables.
 * This allows CI/CD to inject the keystore without committing secrets to version control.
 * If the environment variables are not set, the signing config is skipped (debug builds
 * use the default debug keystore automatically).
 *
 * Build type notes:
 * - **debug**: Default, no minification, allows debugging.
 * - **release**: Enables R8 minification (isMinifyEnabled) and resource shrinking
 *   (isShrinkResources) to reduce APK size. Uses ProGuard rules for keep rules.
 *
 * Packaging exclusions:
 * The google-api-client-android library bundles META-INF files that conflict with
 * other libraries. We exclude them to avoid duplicate file errors during packaging.
 *
 * @see build.gradle.kts (root) for plugin declarations
 * @see gradle/libs.versions.toml for version catalog
 */
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    // Android application plugin -- builds the APK/AAB.
    alias(libs.plugins.android.application)
    // Kotlin for Android -- compiles Kotlin to JVM bytecode with Android support.
    alias(libs.plugins.kotlin.android)
    // Kotlin Compose compiler plugin -- enables @Composable function compilation.
    alias(libs.plugins.kotlin.compose)
    // KSP (Kotlin Symbol Processing) -- compile-time code generation for Room and Hilt.
    alias(libs.plugins.ksp)
    // Hilt plugin -- configures Hilt annotation processing for dependency injection.
    alias(libs.plugins.hilt)
    // Kotlin Serialization plugin -- enables @Serializable for type-safe navigation routes.
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The application's package namespace -- used for R class generation and BuildConfig.
    namespace = "com.johnsonyuen.signalbackup"
    // API level to compile against. 35 = Android 15.
    compileSdk = 35

    defaultConfig {
        // Unique application ID on the Play Store and device.
        applicationId = "com.johnsonyuen.signalbackup"
        // Minimum supported API level. 26 = Android 8.0 (Oreo).
        // Chosen because it provides java.time APIs natively and notification channels.
        minSdk = 26
        // API level we test against and optimize for.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val buildTime = ZonedDateTime.now(ZoneId.of("Australia/Sydney"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm a z"))
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    // Release signing configuration.
    // Reads keystore credentials from environment variables so secrets are never
    // committed to version control. Set these in CI/CD or local environment:
    //   KEYSTORE_FILE     - Path to the .jks or .keystore file
    //   KEYSTORE_PASSWORD - Password for the keystore
    //   KEY_ALIAS         - Alias of the signing key within the keystore
    //   KEY_PASSWORD      - Password for the specific key alias
    signingConfigs {
        create("release") {
            val keystoreFilePath = System.getenv("KEYSTORE_FILE")
            if (keystoreFilePath != null) {
                storeFile = file(keystoreFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 code shrinking -- removes unused code, obfuscates names, optimizes bytecode.
            isMinifyEnabled = true
            // Resource shrinking -- removes unused drawable/layout/string resources.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the release signing config defined above.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        // Java 17 is required by the current Android Gradle Plugin and Hilt.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // Enables Jetpack Compose in this module.
        compose = true
        // Enables BuildConfig class generation (for BUILD_TIME field).
        buildConfig = true
    }

    packaging {
        resources {
            // Exclude META-INF files that cause duplicate-file conflicts.
            // These come from google-api-client-android and its transitive dependencies.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.SF"   // JAR signature files
            excludes += "META-INF/*.DSA"  // JAR signature files
            excludes += "META-INF/*.RSA"  // JAR signature files
        }
    }
}

dependencies {
    // ---------------------------------------------------------------------------
    // Core Android + Lifecycle
    // ---------------------------------------------------------------------------
    // Android KTX extensions (e.g., Context.startActivity() with reified types).
    implementation(libs.androidx.core.ktx)
    // Lifecycle runtime -- LifecycleOwner, lifecycle-aware coroutines.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Lifecycle extensions for Compose (collectAsStateWithLifecycle()).
    implementation(libs.androidx.lifecycle.runtime.compose)
    // ViewModel integration with Compose (viewModel() helper).
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Activity integration with Compose (setContent {}, ComponentActivity).
    implementation(libs.androidx.activity.compose)

    // ---------------------------------------------------------------------------
    // Jetpack Compose
    // ---------------------------------------------------------------------------
    // BOM (Bill of Materials) -- ensures all Compose libraries use compatible versions.
    implementation(platform(libs.androidx.compose.bom))
    // Core Compose UI runtime.
    implementation(libs.androidx.compose.ui)
    // Graphics layer for Compose (Canvas, brushes, etc.).
    implementation(libs.androidx.compose.ui.graphics)
    // @Preview annotation support.
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Material Design 3 components (Button, Card, Scaffold, TopAppBar, etc.).
    implementation(libs.androidx.compose.material3)
    // Extended Material Icons (CloudUpload, Schedule, AccountCircle, etc.).
    implementation(libs.androidx.compose.material.icons.extended)
    // Compose UI tooling for debug builds (Layout Inspector, Preview rendering).
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------
    // Navigation Compose -- NavHost, NavController, type-safe composable<Route> DSL.
    implementation(libs.androidx.navigation.compose)

    // ---------------------------------------------------------------------------
    // Hilt (Dependency Injection)
    // ---------------------------------------------------------------------------
    // Hilt runtime -- @HiltAndroidApp, @AndroidEntryPoint, @Inject, @Module, etc.
    implementation(libs.hilt.android)
    // Hilt annotation processor (via KSP) -- generates DI code at compile time.
    ksp(libs.hilt.compiler)
    // Hilt integration with Navigation Compose -- hiltViewModel() composable function.
    implementation(libs.hilt.navigation.compose)

    // ---------------------------------------------------------------------------
    // Room (Local Database)
    // ---------------------------------------------------------------------------
    // Room runtime -- @Entity, @Dao, @Database annotations and runtime.
    implementation(libs.room.runtime)
    // Room KTX -- Coroutines support (suspend DAO methods, Flow return types).
    implementation(libs.room.ktx)
    // Room annotation processor (via KSP) -- generates SQL implementations of DAOs.
    ksp(libs.room.compiler)

    // ---------------------------------------------------------------------------
    // DataStore (Preferences Storage)
    // ---------------------------------------------------------------------------
    // Preferences DataStore -- type-safe key-value storage replacing SharedPreferences.
    implementation(libs.datastore.preferences)

    // ---------------------------------------------------------------------------
    // WorkManager (Background Task Scheduling)
    // ---------------------------------------------------------------------------
    // WorkManager KTX -- CoroutineWorker, OneTimeWorkRequest, PeriodicWorkRequest.
    implementation(libs.work.runtime.ktx)
    // Hilt integration with WorkManager -- @HiltWorker annotation.
    implementation(libs.hilt.work)
    // Hilt WorkManager annotation processor (via KSP).
    ksp(libs.hilt.work.compiler)

    // ---------------------------------------------------------------------------
    // Google Drive API
    // ---------------------------------------------------------------------------
    // Google API client for Android -- HTTP transport, JSON parsing, credential management.
    implementation(libs.google.api.client.android)
    // Google Drive API v3 service -- Drive.Files.create(), .list(), etc.
    implementation(libs.google.api.services.drive)

    // ---------------------------------------------------------------------------
    // Google Sign-In / Credentials
    // ---------------------------------------------------------------------------
    // Play Services Auth -- GoogleSignIn, GoogleSignInOptions, GoogleSignInClient.
    // Note: GoogleSignIn is deprecated but still functional for Drive scope requests.
    implementation(libs.play.services.auth)
    // AndroidX Credentials API -- modern credential management (future migration path).
    implementation(libs.credentials)
    // Credentials integration with Play Services.
    implementation(libs.credentials.play.services)
    // Google Identity library -- AuthorizationClient for OAuth consent.
    implementation(libs.googleid)

    // ---------------------------------------------------------------------------
    // Jackson (JSON Parsing)
    // ---------------------------------------------------------------------------
    // Jackson Core -- required by google-api-client for JSON serialization/deserialization.
    implementation(libs.jackson.core)

    // ---------------------------------------------------------------------------
    // DocumentFile (Storage Access Framework)
    // ---------------------------------------------------------------------------
    // AndroidX DocumentFile -- provides a File-like API over SAF content URIs.
    // Used to list and read .backup files from the user-selected local folder.
    implementation(libs.androidx.documentfile)

    // ---------------------------------------------------------------------------
    // Coroutines
    // ---------------------------------------------------------------------------
    // Coroutines for Android -- Dispatchers.Main, lifecycle-aware coroutine scopes.
    implementation(libs.kotlinx.coroutines.android)
    // Coroutines integration with Play Services -- .await() on Google Tasks.
    implementation(libs.kotlinx.coroutines.play.services)

    // ---------------------------------------------------------------------------
    // Serialization
    // ---------------------------------------------------------------------------
    // Kotlinx Serialization JSON -- used by Navigation Compose for type-safe routes.
    implementation(libs.kotlinx.serialization.json)
}

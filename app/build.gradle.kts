import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // Kotlin compilation is built into AGP 9; only compiler plugins are applied here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/** Gradle property, falling back to local.properties (not committed). */
val localProperties: Properties = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull
    if (text != null) load(text.reader())
}

fun configValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name)

val appVersionCode: Int = configValue("versionCode")?.toIntOrNull() ?: 1
val appVersionName: String = configValue("versionName") ?: "1.0"
val webClientId: String = configValue("signalbackup.webClientId")
    ?: "708439544712-sfhh5anisbh3mik9tt1l638r86c7mcvp.apps.googleusercontent.com"

/** Commit time of HEAD (reproducible, unlike now()); "unknown" outside a git checkout. */
val buildTime: String = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%cI")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

val keystoreFile: String? = providers.environmentVariable("KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.johnsonyuen.signalbackup"
    // 37: current androidx Compose/lifecycle/hilt artifacts are compiled against API 37 and require it.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.johnsonyuen.signalbackup"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
    }

    signingConfigs {
        keystoreFile?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Unsigned release APK when no keystore is provided (CI builds on branches/PRs).
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
        // Print issues to the console so CI can turn them into annotations.
        textReport = true
        textOutput = file("stdout")
    }

    packaging {
        resources {
            // Duplicate META-INF files from google-api-client and its transitive dependencies.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // Core + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Google Drive REST client
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)

    // Auth: Credential Manager (sign-in) + Play services AuthorizationClient (Drive scope)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity.googleid)

    // Storage Access Framework
    implementation(libs.androidx.documentfile)

    // Coroutines (+ Task.await())
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Serialization (type-safe navigation routes)
    implementation(libs.kotlinx.serialization.json)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.arch.core.testing)
}

// Unit tests stream multi-chunk (8 MiB) fake files and load Robolectric; the default 512 MB test heap is too small.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    maxParallelForks = 2
    forkEvery = 20
    // Print exception messages and stack traces so CI annotations show why a test failed.
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

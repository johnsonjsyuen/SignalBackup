# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Subagents
always use and parallelize if possible

## Build Commands

```bash
./gradlew assembleDebug       # Build debug APK
./gradlew assembleRelease     # Build release APK (requires signing env vars)
./gradlew lint                # Run Android Lint
./gradlew build               # Full build (assemble + lint)
```

**JDK requirement:** Temurin JDK 20. GraalCE 21 causes `jlink` errors with Android builds. The JDK path is set in `gradle.properties` via `org.gradle.java.home`.

No tests exist yet. No ktlint/detekt configured — linting is Android Lint only.

## Architecture

Single-module Android app using **Clean Architecture** with three layers:

- **UI Layer** (`ui/`): Jetpack Compose screens + ViewModels with StateFlow. Single Activity (`MainActivity`) with three-tab bottom navigation (Home, History, Settings) using type-safe Navigation Compose routes via `@Serializable`.
- **Domain Layer** (`domain/`): Use cases and domain models. No Android framework dependencies except Context for SAF file access.
- **Data Layer** (`data/`): Repository implementations, Room database, Preferences DataStore, Google Drive REST API client.

### Upload Data Flow

```
HomeScreen → HomeViewModel → ManualUploadUseCase → WorkManager
→ UploadWorker (foreground service) → PerformUploadUseCase → DriveRepository → GoogleDriveService → Google Drive
```

Uploads use a **manual resumable upload protocol** — the session URI and byte offset are persisted to DataStore, surviving app kills and WorkManager retries. Sessions are valid ~1 week on Google's side.

### Dependency Injection (Hilt)

- `di/AppModule.kt`: Room database, DataStore, SettingsDataStore, WorkManager (`@Provides @Singleton`)
- `di/DriveModule.kt`: GoogleAccountCredential, AuthorizationClient
- `di/RepositoryModule.kt`: `@Binds` for all repository interfaces

**SettingsDataStore** uses `@Provides` (not `@Inject` constructor) to avoid Hilt duplicate binding errors with the DataStore file-level delegate.

### WorkManager

Custom initialization — default AndroidX Startup initializer is disabled in the manifest. `SignalBackupApp` implements `Configuration.Provider` with `HiltWorkerFactory`. `UploadWorker` uses `@HiltWorker` and runs as a foreground service with `DATA_SYNC` type.

### Google Auth

`GoogleAccountCredential` requires `selectedAccountName` set before every Drive API call. `GoogleDriveService.ensureAccount()` reads the email from DataStore and sets it on the credential. `UserRecoverableAuthIOException` surfaces as `UploadStatus.NeedsConsent`.

## Key Build Quirks

- **Version catalog**: All dependency versions in `gradle/libs.versions.toml`
- **Packaging excludes** in `app/build.gradle.kts` for google-api-client conflicts: `META-INF/DEPENDENCIES`, `INDEX.LIST`, `*.SF`, `*.DSA`, `*.RSA`
- **settings.gradle.kts**: Uses `dependencyResolutionManagement` (not the deprecated `dependencyResolution`)
- **KSP** (not kapt) for Room, Hilt, and kotlinx.serialization annotation processing
- **Proguard rules** in `app/proguard-rules.pro` for Google API client, Jackson, kotlinx.serialization
- After Gradle version upgrades, a clean build (`./gradlew clean`) is typically needed

## CI/CD

GitHub Actions with two workflows:
- `.github/workflows/build.yml`: On push/PR to `main` — builds debug APK + runs lint
- `.github/workflows/release.yml`: On `v*` tag push — builds signed release APK, creates GitHub Release

Release signing uses secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

# Signal Backup - Implementation Guide

A comprehensive guide to understanding how this Android app is built, from architecture to individual implementation details.

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack](#2-tech-stack)
3. [Architecture Deep Dive](#3-architecture-deep-dive)
4. [Key Flows](#4-key-flows)
5. [Android Concepts Explained](#5-android-concepts-explained)
6. [How to Extend](#6-how-to-extend)
7. [Common Pitfalls](#7-common-pitfalls)
8. [Build and Run](#8-build-and-run)

---

## 1. Project Overview

**Signal Backup** is an Android application that automatically backs up Signal Messenger's encrypted backup files to Google Drive. It:

- Lets users pick a local folder containing Signal `.backup` files.
- Lets users sign in with Google and select a Drive destination folder.
- Uploads the latest backup file to Google Drive on demand or on a daily schedule.
- Keeps a history of all upload attempts (success/failure) in a local database.
- Supports light, dark, and system-default themes.

### App Package

```
com.johnsonyuen.signalbackup
```

### Minimum SDK

- **minSdk 26** (Android 8.0 Oreo) -- provides `java.time` APIs, notification channels, and SAF improvements.
- **targetSdk / compileSdk 35** (Android 15).

---

## 2. Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Kotlin 2.0.21 | Primary language, with coroutines for async |
| UI | Jetpack Compose + Material3 | Declarative UI framework |
| DI | Hilt (Dagger) 2.53.1 | Dependency injection |
| Database | Room 2.6.1 | SQLite ORM for upload history |
| Preferences | Preferences DataStore 1.1.1 | Type-safe key-value storage |
| Background Work | WorkManager 2.10.0 | Scheduled periodic uploads |
| Cloud Storage | Google Drive API v3 | File upload to Drive |
| Authentication | Google Sign-In + Identity API | OAuth2 authentication |
| Navigation | Navigation Compose 2.8.5 | Type-safe routing |
| Serialization | kotlinx.serialization 1.7.3 | Route serialization for navigation |
| Build | Gradle 8.13 + AGP 8.13.2 | Build system with version catalog |
| Code Generation | KSP 2.0.21-1.0.28 | Compile-time annotation processing |

### Version Catalog

All dependency versions are centralized in `gradle/libs.versions.toml`. The app's `build.gradle.kts` references them via type-safe `libs.*` aliases (e.g., `libs.hilt.android`, `libs.room.runtime`).

---

## 3. Architecture Deep Dive

### 3.1 Package Structure

```
com.johnsonyuen.signalbackup/
├── SignalBackupApp.kt          # Application class (Hilt entry point, WorkManager config)
├── MainActivity.kt             # Single Activity hosting Compose UI
├── data/
│   ├── local/
│   │   ├── entity/
│   │   │   ├── UploadHistoryEntity.kt   # Room entity (database table)
│   │   │   └── UploadHistoryMapper.kt   # Entity -> domain model mapper
│   │   ├── db/
│   │   │   ├── AppDatabase.kt           # Room database definition
│   │   │   └── UploadHistoryDao.kt      # Room DAO (SQL queries)
│   │   └── datastore/
│   │       └── SettingsDataStore.kt     # Preferences DataStore wrapper
│   ├── remote/
│   │   └── GoogleDriveService.kt        # Google Drive API wrapper
│   └── repository/
│       ├── SettingsRepository.kt        # Interface
│       ├── SettingsRepositoryImpl.kt    # DataStore-backed implementation
│       ├── UploadHistoryRepository.kt   # Interface
│       ├── UploadHistoryRepositoryImpl.kt # Room-backed implementation
│       ├── DriveRepository.kt           # Interface
│       └── DriveRepositoryImpl.kt       # Drive API-backed implementation
├── domain/
│   ├── model/
│   │   ├── UploadStatus.kt             # Sealed interface: Idle/Uploading/Success/Failed/NeedsConsent
│   │   ├── UploadRecord.kt             # Domain model for upload history
│   │   ├── UploadResultStatus.kt       # Enum: SUCCESS/FAILED
│   │   ├── DriveFolder.kt              # Data class: id + name
│   │   └── ThemeMode.kt                # Enum: LIGHT/DARK/SYSTEM
│   └── usecase/
│       ├── PerformUploadUseCase.kt      # Core upload logic
│       └── ScheduleUploadUseCase.kt     # WorkManager scheduling logic
├── di/
│   ├── AppModule.kt                     # Room, DataStore, WorkManager providers
│   ├── DriveModule.kt                   # GoogleAccountCredential, AuthorizationClient
│   └── RepositoryModule.kt             # Interface-to-impl bindings
├── worker/
│   └── UploadWorker.kt                 # Background upload worker
├── ui/
│   ├── theme/
│   │   ├── Color.kt                     # Material3 color tokens
│   │   ├── Type.kt                      # Material3 typography
│   │   └── Theme.kt                     # Theme composable with dynamic colors
│   ├── navigation/
│   │   └── AppNavGraph.kt               # Bottom nav + NavHost
│   ├── component/
│   │   ├── StatusCard.kt                # Upload status display card
│   │   ├── CountdownTimer.kt            # Next upload countdown
│   │   ├── TimePickerDialog.kt          # Schedule time picker
│   │   ├── DriveFolderPickerDialog.kt   # Drive folder browser
│   │   └── HistoryItem.kt              # Upload history record card
│   └── screen/
│       ├── home/
│       │   ├── HomeViewModel.kt         # Upload state + settings
│       │   └── HomeScreen.kt            # Main screen with auth + upload
│       ├── settings/
│       │   ├── SettingsViewModel.kt     # Settings + folder picker state
│       │   └── SettingsScreen.kt        # Configuration UI
│       └── history/
│           ├── HistoryViewModel.kt      # Upload records flow
│           └── HistoryScreen.kt         # History list UI
└── util/
    └── FormatUtils.kt                   # Shared formatting functions
```

### 3.2 Layer Architecture

The app follows a simplified **Clean Architecture** with three layers:

```
┌─────────────────────────────────────────┐
│            UI / Presentation            │  Compose screens, ViewModels
├─────────────────────────────────────────┤
│               Domain                    │  Use cases, domain models
├─────────────────────────────────────────┤
│               Data                      │  Repositories, Room, DataStore, Drive API
└─────────────────────────────────────────┘
```

**Dependency rule**: UI depends on Domain, Domain depends on Data interfaces (not implementations). The DI layer (Hilt modules) wires everything together.

### 3.3 Hilt Dependency Injection

Hilt manages all object creation and lifecycle. Here's how dependencies flow:

```
SignalBackupApp (@HiltAndroidApp)
  └── creates Hilt's SingletonComponent
        ├── AppModule (@Provides)
        │     ├── Room Database + DAO
        │     ├── DataStore<Preferences>
        │     ├── SettingsDataStore
        │     └── WorkManager
        ├── DriveModule (@Provides)
        │     ├── GoogleAccountCredential
        │     └── AuthorizationClient
        └── RepositoryModule (@Binds)
              ├── SettingsRepository -> SettingsRepositoryImpl
              ├── UploadHistoryRepository -> UploadHistoryRepositoryImpl
              └── DriveRepository -> DriveRepositoryImpl
```

**Key Hilt annotations**:
- `@HiltAndroidApp` on `SignalBackupApp` -- triggers Hilt code generation.
- `@AndroidEntryPoint` on `MainActivity` -- enables injection in the Activity.
- `@HiltViewModel` on ViewModels -- enables `hiltViewModel()` in Compose.
- `@HiltWorker` on `UploadWorker` -- enables injection in WorkManager workers.
- `@Inject constructor(...)` on repositories and use cases -- constructor injection.
- `@Provides` in modules -- manual object construction.
- `@Binds` in modules -- interface-to-implementation mapping.
- `@Singleton` -- one instance per app lifetime.

### 3.4 Data Layer

**Room Database** (`data/local/`):
- `UploadHistoryEntity` maps to the `upload_history` table with columns: id, fileName, fileSizeBytes, status, errorMessage, timestamp.
- `UploadHistoryDao` provides `insert()`, `getAll()` (Flow), and `getLatest()` queries.
- `AppDatabase` is the Room database definition (version 2, single entity).

**DataStore** (`data/local/datastore/`):
- `SettingsDataStore` wraps `DataStore<Preferences>` with typed accessor properties (Flows) and setter functions for each setting: googleAccountEmail, localFolderUri, driveFolderId, driveFolderName, scheduleHour, scheduleMinute, themeMode.

**Google Drive** (`data/remote/`):
- `GoogleDriveService` wraps the Google Drive API v3. It uses `GoogleAccountCredential` for auth, reads the account email from DataStore before each call (`ensureAccount()`), and provides `uploadFile()`, `listFolders()`, and `createFolder()`.

**Repositories** (`data/repository/`):
- Each repository has an interface (used by domain/UI layers) and an implementation (in the data layer).
- `SettingsRepository`: Reactive Flows for settings + suspend setters.
- `UploadHistoryRepository`: Returns `Flow<List<UploadRecord>>` (domain models, not entities).
- `DriveRepository`: Thin delegation to `GoogleDriveService`.

### 3.5 Domain Layer

**Models** (`domain/model/`):
- `UploadStatus`: Sealed interface representing the current upload state machine. States: `Idle`, `Uploading`, `Success(fileName, fileSizeBytes)`, `Failed(error)`, `NeedsConsent(consentIntent)`.
- `UploadRecord`: Domain model for historical records (id, fileName, fileSizeBytes, status, errorMessage, timestamp as `Instant`).
- `UploadResultStatus`: Enum (`SUCCESS`, `FAILED`) for persisted results.
- `DriveFolder`: Simple data class (`id`, `name`).
- `ThemeMode`: Enum (`LIGHT`, `DARK`, `SYSTEM`) with `displayName` and `fromString()`.

**Use Cases** (`domain/usecase/`):
- `PerformUploadUseCase`: The core business logic. Reads settings, finds the latest `.backup` file via SAF, uploads to Drive, records the result in Room. Returns an `UploadStatus`.
- `ScheduleUploadUseCase`: Calculates the initial delay to the next scheduled time, creates a `PeriodicWorkRequest`, and enqueues it with `WorkManager`.

### 3.6 Presentation Layer

**ViewModels**:
- `HomeViewModel`: Manages upload status (MutableStateFlow), exposes settings as StateFlows, provides `uploadNow()` and `setGoogleAccountEmail()`.
- `SettingsViewModel`: Exposes all settings as StateFlows, manages Drive folder picker state (folder stack, loading, errors), provides actions for changing settings.
- `HistoryViewModel`: Simply exposes the upload records Flow as a StateFlow.

**Screens**:
- `HomeScreen`: Sign-in flow (GoogleSignIn), consent handling (LaunchedEffect), upload trigger, status card, countdown.
- `SettingsScreen`: SAF folder picker, Drive folder picker dialog, time picker dialog, theme dialog, account management.
- `HistoryScreen`: LazyColumn of HistoryItem cards.

**Navigation**:
- `AppNavGraph` sets up a Scaffold with TopAppBar + NavigationBar + NavHost.
- Routes are `@Serializable data object` classes (type-safe, compile-time checked).
- Tab switching preserves back-stack state with `saveState`/`restoreState`.

---

## 4. Key Flows

### 4.1 App Startup

```
1. Android creates SignalBackupApp (marked @HiltAndroidApp)
     └── Hilt generates and creates the DI component graph
     └── workManagerConfiguration property provides custom config with HiltWorkerFactory

2. Android creates MainActivity (marked @AndroidEntryPoint)
     └── Hilt injects SettingsDataStore
     └── onCreate() reads themeMode from DataStore
     └── setContent { SignalBackupTheme(themeMode) { AppNavGraph() } }

3. AppNavGraph creates Scaffold + NavHost
     └── HomeRoute is the start destination
     └── HomeScreen is rendered via composable<HomeRoute>
     └── hiltViewModel<HomeViewModel>() creates/retrieves the ViewModel
```

### 4.2 Google Sign-In Flow

```
1. User taps "Sign In with Google" on HomeScreen
     └── requestSignIn() builds GoogleSignInOptions with email + DRIVE_FILE scope
     └── Launches GoogleSignIn.getClient(context, gso).signInIntent

2. Google shows account picker / sign-in UI

3. signInLauncher receives ActivityResult
     └── Extracts email from GoogleSignIn.getSignedInAccountFromIntent()
     └── Calls viewModel.setGoogleAccountEmail(email)
     └── SettingsRepository.setGoogleAccountEmail() writes to DataStore
```

### 4.3 Manual Upload Flow

```
1. User taps "Upload Now" on HomeScreen
     └── viewModel.uploadNow() is called
     └── _uploadStatus = Uploading (UI shows spinner)

2. PerformUploadUseCase(context) is invoked:
   a. Read localFolderUri from DataStore
   b. Read driveFolderId from DataStore
   c. Parse URI, create DocumentFile from SAF tree
   d. List files matching *.backup, sort by lastModified, pick newest
   e. Open InputStream via contentResolver
   f. Call driveRepository.uploadFile(inputStream, fileName, mimeType, folderId)
      └── GoogleDriveService.ensureAccount() reads email from DataStore
      └── Sets GoogleAccountCredential.selectedAccountName
      └── Creates Drive.Files.create() request with media upload
      └── Executes the HTTP request to Drive API
   g. On success: insert UploadHistoryEntity into Room, return UploadStatus.Success
   h. On UserRecoverableAuthIOException: return UploadStatus.NeedsConsent(intent)
   i. On other exception: insert failed record into Room, return UploadStatus.Failed

3. _uploadStatus is updated with the result
     └── If NeedsConsent: LaunchedEffect detects it, launches consent Intent
     └── If consent granted: uploadNow() is called again (retry)

4. UI recomposes to show the new status in StatusCard
```

### 4.4 Scheduled Upload Flow

```
1. User changes schedule time in SettingsScreen
     └── TimePickerDialog onConfirm(hour, minute)
     └── viewModel.setScheduleTime(hour, minute)

2. SettingsViewModel:
     └── settingsRepository.setScheduleTime(hour, minute) -- writes to DataStore
     └── scheduleUploadUseCase() -- reschedules WorkManager

3. ScheduleUploadUseCase:
   a. Reads hour + minute from DataStore
   b. Calculates initialDelay = Duration.between(now, nextTarget)
   c. Creates PeriodicWorkRequestBuilder<UploadWorker>(24h, 30min flex)
   d. Sets constraints: CONNECTED network + battery not low
   e. Sets BackoffPolicy.EXPONENTIAL (15min base)
   f. Enqueues with ExistingPeriodicWorkPolicy.UPDATE

4. At the scheduled time, WorkManager runs UploadWorker:
   a. setForeground(createForegroundInfo()) -- shows notification
   b. performUploadUseCase(applicationContext) -- same upload logic
   c. Returns Result.success() or Result.retry() (up to 3 attempts)
```

### 4.5 Drive Folder Selection Flow

```
1. User taps "Select" on the Drive Folder card in SettingsScreen
     └── viewModel.loadDriveFolders() -- fetches root-level folders from Drive API
     └── showDrivePicker = true

2. DriveFolderPickerDialog is shown:
     └── LazyColumn displays the folders returned by Drive API
     └── User taps a folder -> viewModel.navigateToFolder(folder)
         └── Pushes folder onto _folderStack
         └── loadDriveFolders(folder.id) -- fetches children
     └── Back arrow -> viewModel.navigateUp()
         └── Pops from _folderStack
         └── Reloads parent's children
     └── "New Folder" icon -> nested AlertDialog for folder creation
         └── viewModel.createFolder(name) -- Drive API call
         └── Reloads current folder to show the new folder

3. User taps "Select This Folder":
     └── viewModel.setDriveFolder(folder) -- saves id + name to DataStore
     └── showDrivePicker = false
```

---

## 5. Android Concepts Explained

### 5.1 Jetpack Compose

Compose is Android's modern declarative UI toolkit. Instead of XML layouts, you write Kotlin functions annotated with `@Composable`:

```kotlin
@Composable
fun Greeting(name: String) {
    Text("Hello, $name!")
}
```

**Key concepts used in this app**:
- **State hoisting**: UI state lives in ViewModels, passed down to composables.
- **MutableStateFlow -> StateFlow -> collectAsStateWithLifecycle()**: Chain from ViewModel state to Compose recomposition.
- **remember { mutableStateOf() }**: Local state that survives recompositions (used for dialog visibility, form inputs).
- **LaunchedEffect(key)**: Runs a coroutine side-effect when the key changes (used for consent Intent launching, error snackbar).
- **rememberLauncherForActivityResult()**: Registers an Activity result callback for the Compose lifecycle.

### 5.2 Hilt Dependency Injection

Hilt eliminates manual object construction. Instead of:
```kotlin
val dao = Room.databaseBuilder(...).build().uploadHistoryDao()
val repo = UploadHistoryRepositoryImpl(dao)
val viewModel = HistoryViewModel(repo)
```

You write:
```kotlin
@HiltViewModel
class HistoryViewModel @Inject constructor(
    uploadHistoryRepository: UploadHistoryRepository
) : ViewModel()
```

Hilt resolves the entire dependency chain automatically.

### 5.3 Room Database

Room is an ORM over SQLite. You define:
- **Entity** (`@Entity`): A Kotlin data class that maps to a database table.
- **DAO** (`@Dao`): An interface with `@Query`, `@Insert`, `@Delete` methods.
- **Database** (`@Database`): Declares entities and DAO accessors.

Room generates all SQL at compile time (via KSP). Flow return types make queries reactive -- the UI automatically updates when data changes.

### 5.4 WorkManager

WorkManager is for **reliable background work** that must execute even if the app is closed or the device reboots. It is not for real-time work.

In this app, `PeriodicWorkRequest` runs the upload every 24 hours:
```
PeriodicWorkRequestBuilder<UploadWorker>(24, HOURS, 30, MINUTES)
```

The 30-minute flex window means WorkManager can execute anytime in the last 30 minutes of the 24-hour period, optimizing for battery.

**Constraints** ensure the upload only runs when conditions are met (network available, battery not low).

### 5.5 Google Drive API Authentication

The auth flow has multiple components:

1. **GoogleSignIn**: Shows the account picker, returns a signed-in account with an email.
2. **GoogleAccountCredential**: A credential object that uses the Android account system to obtain OAuth2 tokens. Set `selectedAccountName` before each API call.
3. **UserRecoverableAuthIOException**: Thrown when the OAuth token is expired or a new scope hasn't been granted. Contains an Intent to show the consent dialog.
4. **AuthorizationClient**: Part of the newer Identity API, used for requesting authorization.

### 5.6 Storage Access Framework (SAF)

SAF lets users grant apps access to specific directories without broad storage permissions:

1. `ActivityResultContracts.OpenDocumentTree()` launches the system folder picker.
2. Returns a `content://` URI representing the selected directory.
3. `takePersistableUriPermission()` makes the URI survive app restarts.
4. `DocumentFile.fromTreeUri()` wraps the URI for file-like operations.
5. `.listFiles()` returns the files; `.uri` gives a URI to open an InputStream.

### 5.7 DataStore vs SharedPreferences

DataStore is the modern replacement for SharedPreferences:
- **Asynchronous**: All reads are Kotlin Flows (non-blocking), all writes are suspend functions.
- **Type-safe**: Keys are typed (`stringPreferencesKey`, `intPreferencesKey`).
- **Coroutine-based**: Integrates naturally with Kotlin's structured concurrency.
- **Single instance**: MUST have exactly one DataStore instance per file name.

### 5.8 Navigation Compose with Type-Safe Routes

Navigation Compose 2.8+ supports type-safe routing via `@Serializable` objects:

```kotlin
@Serializable data object HomeRoute
@Serializable data object SettingsRoute

// In NavHost:
composable<HomeRoute> { HomeScreen() }

// Navigating:
navController.navigate(SettingsRoute)
```

No more string-based routes like `"home"` or `"settings/{id}"`.

---

## 6. How to Extend

### Add a New Setting

1. Add a `Preferences.Key` in `SettingsDataStore.kt`.
2. Add a Flow property and a setter function in `SettingsDataStore`.
3. Add them to the `SettingsRepository` interface and `SettingsRepositoryImpl`.
4. Expose as a `StateFlow` in the appropriate ViewModel.
5. Add a UI card in `SettingsScreen.kt`.

### Add a New Screen/Tab

1. Create a `@Serializable data object` route in `AppNavGraph.kt`.
2. Create the screen composable and ViewModel.
3. Add a `BottomNavItem` entry in `AppNavGraph`.
4. Add a `composable<YourRoute>` entry in the NavHost.

### Add a New Use Case

1. Create a class in `domain/usecase/` with `@Inject constructor(...)`.
2. Inject any repositories or services you need.
3. Define `suspend operator fun invoke(...)` as the entry point.
4. Inject the use case into the relevant ViewModel.

### Add a New Database Table

1. Create a `@Entity` data class in `data/local/entity/`.
2. Create a `@Dao` interface in `data/local/db/`.
3. Add the entity to `@Database(entities = [...])` in `AppDatabase.kt`.
4. Add a DAO accessor method to `AppDatabase`.
5. Increment the database version (or use `fallbackToDestructiveMigration()` during development).
6. Provide the DAO in `AppModule.kt`.

---

## 7. Common Pitfalls

### DataStore Singleton Requirement

**Problem**: Creating multiple DataStore instances for the same file causes a runtime exception.

**Solution**: The `by preferencesDataStore(name = "settings")` delegate is defined at file level in `AppModule.kt`, ensuring exactly one instance. Hilt's `@Singleton` scope ensures the same instance is injected everywhere.

### GoogleAccountCredential Must Have Email Set

**Problem**: Drive API calls fail with a generic error if `selectedAccountName` is not set on the credential.

**Solution**: `GoogleDriveService.ensureAccount()` reads the email from DataStore before every API call. This is called at the top of `uploadFile()`, `listFolders()`, and `createFolder()`.

### WorkManager + Hilt Requires Custom Initialization

**Problem**: `@HiltWorker` workers need `HiltWorkerFactory`, but WorkManager's default initializer does not know about Hilt.

**Solution**:
1. Disable the default initializer in `AndroidManifest.xml` (tools:node="remove").
2. `SignalBackupApp` implements `Configuration.Provider`.
3. `workManagerConfiguration` returns a config with `HiltWorkerFactory`.

### Foreground Service on Android 12+

**Problem**: `setForeground()` throws `ForegroundServiceStartNotAllowedException` when the app is in the background on Android 12+.

**Solution**: `UploadWorker.doWork()` wraps `setForeground()` in a try-catch. The worker continues running even if the foreground promotion fails.

### SAF URI Permissions are Revocable

**Problem**: The content URI returned by `OpenDocumentTree` expires when the app restarts unless you take a persistable permission.

**Solution**: `SettingsScreen` calls `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` immediately after the user selects a folder.

### ViewModels Must Not Hold Activity Context

**Problem**: ViewModels outlive Activities. Holding an Activity context causes memory leaks.

**Solution**: ViewModels use `@ApplicationContext` to inject the Application context, which has the same lifetime as the singleton component.

### Compose Flow Collection

**Problem**: Using `.collectAsState()` does not stop collection when the UI is in the background, wasting resources.

**Solution**: Use `.collectAsStateWithLifecycle()` from `lifecycle-runtime-compose`, which automatically pauses collection when the Lifecycle is below STARTED.

---

## 8. Build and Run

### Prerequisites

- **Android Studio** (Ladybug or newer recommended)
- **JDK 17** (Temurin recommended; GraalCE may cause `jlink` errors)
- **Android SDK 35** installed via SDK Manager
- A **Google account** for testing Drive uploads
- A **physical device or emulator** with Google Play Services

### Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing environment variables)
KEYSTORE_FILE=/path/to/keystore.jks \
KEYSTORE_PASSWORD=password \
KEY_ALIAS=alias \
KEY_PASSWORD=password \
./gradlew assembleRelease
```

### Running

1. Open the project in Android Studio.
2. Select a device/emulator with Google Play Services.
3. Click Run (or `./gradlew installDebug`).

### Project Configuration

- **Gradle wrapper**: 8.13
- **Android Gradle Plugin**: 8.13.2
- **Kotlin**: 2.0.21
- **Java compatibility**: 17
- **JDK note**: Set `org.gradle.java.home` in `gradle.properties` if your default JDK is not compatible. Temurin JDK 20 or 21 works well (avoid GraalCE for Android builds).

### First Run Setup

1. Launch the app.
2. Tap "Sign In with Google" and authenticate.
3. Go to Settings and select:
   - A **local folder** containing Signal backup files (`.backup` extension).
   - A **Google Drive folder** as the upload destination.
   - Optionally change the daily upload **schedule time** (default: 3:00 AM).
4. Return to Home and tap "Upload Now" to test.

---

*This guide was generated to help understand the Signal Backup app implementation. Each source file contains detailed comments explaining the code, Android concepts, and architectural decisions.*

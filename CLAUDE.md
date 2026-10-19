# CLAUDE.md

Guidance for coding agents working in this repository.

## Subagents
Always use subagents and parallelise where possible.

## Build and test

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests (JVM; Robolectric where Android classes are needed)
./gradlew lintDebug            # Android Lint, abortOnError = true (config: app/lint.xml)
./gradlew assembleRelease      # minified release; signed only when KEYSTORE_FILE etc. are set
```

JDK 21. compileSdk 37 / targetSdk 36 / minSdk 26, JVM target 17. All versions live in
`gradle/libs.versions.toml`. AGP 9 has built-in Kotlin: only compiler plugins (compose,
serialization), KSP, Hilt and the Room plugin are applied in `app/build.gradle.kts`. KSP, not kapt.
`BuildConfig.WEB_CLIENT_ID` comes from the `signalbackup.webClientId` Gradle property or
`local.properties`; `BuildConfig.BUILD_TIME` is HEAD's commit time.

If the environment has no Android SDK, GitHub Actions CI is the only compiler: be exact about
imports and API signatures, and don't invent APIs.

## Architecture

Single module, package `com.johnsonyuen.signalbackup` (`app/src/main/java/...`):

| Package | Contents |
|---|---|
| `domain/upload/` | Upload engine: `FileUploader` (one file, resumable or small multipart, verify), `ChunkUploader` (8 MiB chunks, retry, re-seek), `UploadVerifier` (size + MD5), `UploadRetryPolicy`, `NetworkPolicy` (Wi-Fi only), `SafLocalFileSource`, `Md5` |
| `domain/mirror/` | `BackupFolderScanner` (SAF tree walk, skip rules, backup-item detection), `MirrorSyncer` (folders, adopt-or-upload, `synced_file` records), `BackupNames`, `Staleness` (48 h), `DocumentTreeSource` |
| `domain/usecase/` | `PerformUploadUseCase` (one run), `GarbageCollectUseCase` (Clean Up), `ResolveDriveFolderUseCase`, `ManualUploadUseCase` |
| `domain/scheduling/` | `UploadScheduler`, `UploadRunState`, `UploadProgressTracker` interfaces |
| `domain/model/` | `UploadResult`/`FailureReason`, Drive models and exceptions, sessions, settings, records |
| `work/` | `UploadSchedulerImpl` (unique periodic `signal_backup_daily` + expedited `signal_backup_manual`), `DailySchedule` (DST-aware next run) |
| `worker/` | `UploadWorker` (`@HiltWorker`, dataSync foreground, Wi-Fi/wake locks, maps results to retry/failure/notifications), `TransferSpeedMeter` |
| `notification/` | `BackupNotifier(Impl)` (failure / consent / stale, each with a fix action extra), `NotificationChannels`, `UploadProgressNotifications` |
| `data/auth/` | `DriveAuthorizer(Impl)`: Play services `AuthorizationClient`, `drive.file` scope, token invalidate on 401, revoke on sign-out |
| `data/repository/` | Interfaces + impls; `DriveRepositoryImpl` is a Drive v3 REST client on google-http-client with bearer tokens |
| `data/local/` | Room DB `signal_backup_db`, **schema v5**, migrations in `DatabaseMigrations` |
| `di/` | `AppModule` (Room, WorkManager, `Clock`), `DriveModule`, `RepositoryModule`, `WorkModule`; `domain/upload/UploadModule` |
| `ui/` | Compose screens (Home, History, Settings) + ViewModels, `consent/DriveAuthorizationFlow`, `navigation/FixActionBus` (routes notification fix actions) |

Room v5 tables: `app_settings` (single row id=1), `upload_history`, `upload_session` (single
in-flight resumable upload), `synced_file` (PK `relativePath`; one row per mirrored file/folder).

Flow: `UploadWorker` → `PerformUploadUseCase` → `NetworkPolicy` → `ResolveDriveFolderUseCase` →
`BackupFolderScanner` → `MirrorSyncer` → `FileUploader` → `DriveRepository`; then staleness check and
one history row. `SignalBackupApp` (Hilt `Configuration.Provider`; default WorkManager initializer
removed in the manifest) calls `applySchedule()` on start. Sign-in is Credential Manager (email only);
Drive authorization follows sign-in and is re-requested from a notification on `NeedsConsent`.

## Invariants (don't break these)

- **Inject `java.time.Clock`** (from `AppModule`); never `System.currentTimeMillis()` / `now()` without it.
- **Scope is `drive.file` only.** The app sees only what it created. Missing/inaccessible folder →
  `ResolveDriveFolderUseCase` creates "Signal Backup" and clears `synced_file` and the session.
- **Everything the app creates on Drive carries the `appProperties` tag** `signalBackup=1`
  (`DriveFileMeta.uploadedByApp`). Clean Up relies on it.
- **Trash, never delete.** Nothing is removed from Drive automatically; Clean Up keeps the 3 newest
  backup items, never touches locally-present items, and is blocked unless the newest Drive item is verified.
- **One history row per run** (written by `PerformUploadUseCase` under `NonCancellable`), except
  `AlreadyRunning`, which records nothing.
- **Process-wide upload mutex** in `PerformUploadUseCase`'s companion: a second caller gets `AlreadyRunning`.
- **Migrations must never be destructive** (no `fallbackToDestructiveMigration`). Each schema version
  needs a migration path and a test in `app/src/test/.../data/local/DatabaseMigrationTest.kt`.
  Bumping the version means a new `MIGRATION_n_n+1` in `DatabaseMigrations.ALL` plus tests.
- Never log tokens, session URIs, account emails, file URIs or HTTP bodies.
- Use Hilt constructor injection; `@Singleton` where state is shared.

## CI

`.github/workflows/build.yml` (every push, PRs to `main`): assembleDebug → testDebugUnitTest → lintDebug
→ assembleRelease (unsigned) on JDK 21, uploading APK, test/lint reports, Room schemas and logs.
`release.yml` (`v*` tags, `release` environment) builds and signs the APK and publishes a GitHub Release.

**Error annotations:** each Gradle step tees its log to `ci-logs/<task>.log`; on failure
`.github/scripts/annotate-gradle-errors.py` turns Kotlin/javac errors, lint errors (lint prints a text
report to stdout), failed tests and "What went wrong" blocks into `::error` annotations (9 individual
+ 1 folded) and a step summary. Read those annotations (e.g. `gh run view --log-failed`, or the
check-run annotations API) to fix CI failures. See `CI_CD_GUIDE.md`.

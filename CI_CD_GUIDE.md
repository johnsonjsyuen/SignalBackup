# CI/CD Guide

Two GitHub Actions workflows live in `.github/workflows/`, plus Dependabot in `.github/dependabot.yml`.
All third-party actions are pinned to commit SHAs (with the version in a comment), and workflows
start with `permissions: {}` and grant only what each job needs.

## CI build (`build.yml`)

**Triggers:** every push to any branch, and pull requests targeting `main`. Runs for the same ref
cancel each other (`concurrency` with `cancel-in-progress`).

**Job "Build, test & lint"** (`ubuntu-latest`, 45 min timeout, `contents: read`):

1. Checkout, Temurin JDK 21, `gradle/actions/setup-gradle` (build cache).
2. Decode the optional `DEBUG_KEYSTORE_BASE64` secret to `$RUNNER_TEMP/debug.keystore` and export
   `DEBUG_KEYSTORE_FILE`, so debug APKs have a stable SHA-1. If it is missing (including PRs from
   forks), a warning is emitted and the runner's throwaway debug key is used: that APK cannot sign in
   with Google. The keystore is deleted at the end of the job.
3. `./gradlew assembleDebug`, then print the debug APK's signing certificate (SHA-1/SHA-256) to the
   step summary.
4. `./gradlew testDebugUnitTest`
5. `./gradlew lintDebug` (lint has `abortOnError = true`; config in `app/lint.xml`)
6. `./gradlew assembleRelease`: unsigned, checks that R8/minification works.

Every Gradle call gets `-Psignalbackup.webClientId=$WEB_CLIENT_ID` when the repository variable
`WEB_CLIENT_ID` is non-empty; otherwise the default in `app/build.gradle.kts` is used.

Steps 4–6 run only if the debug build succeeded, and each runs even if an earlier one of them failed,
so one push reports compile, test and lint problems together.

**Artifacts** (Actions run page): `debug-apk` (14 days), `test-reports`, `lint-reports` (14 days),
`room-schemas` (exported Room schema JSON from `app/schemas/`, 30 days), `gradle-logs` (7 days).

### Error annotations

Each Gradle step pipes its output through `tee ci-logs/<task>.log`. If the task fails, the step runs
`python3 -I .github/scripts/annotate-gradle-errors.py <log> <task>`, which emits GitHub `::error`
annotations for:

- Kotlin compiler errors (`e: file:///…:line:col …`, with file/line) and javac errors,
- Lint errors (`app/build.gradle.kts` sends lint's text report to stdout for this),
- failed tests (`Class > test FAILED` plus a few lines of detail),
- Gradle "What went wrong" blocks and other `error:` lines.

GitHub shows at most 10 annotations per step, so the first 9 are individual and the rest are folded
into one. Everything is also written to the job's step summary. If nothing is recognised, the log tail
is annotated instead. Annotations appear on the run summary, on PR diffs, and through
`gh run view <id> --log-failed` or the check-runs annotations API.

## Release (`release.yml`)

**Trigger:** pushing a tag matching `v*`. The job runs in the **`release` environment**, so you can
add protection rules (required reviewers, tag restrictions) under Settings › Environments.
Permissions: `contents: write` (create the release), `id-token: write` + `attestations: write`
(provenance).

Steps:

1. Checkout, JDK 21, Gradle (cache read-only).
2. **Derive version from the tag.** The tag must be `vMAJOR.MINOR.PATCH` with an optional `-…`/`+…`
   suffix; anything else fails the run.
   - `versionName` = tag without the `v` (e.g. `1.2.3-beta1`)
   - `versionCode` = `MAJOR*1000000 + MINOR*1000 + PATCH` (the suffix is ignored, so publish
     pre-releases with a patch number higher than the last stable release)
3. Decode `KEYSTORE_BASE64` into `$RUNNER_TEMP/release.keystore` (fails if the secret is missing).
4. `./gradlew assembleRelease -PversionName=… -PversionCode=…` (plus `-Psignalbackup.webClientId`
   if the `WEB_CLIENT_ID` variable is set) with `KEYSTORE_FILE`,
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` in the environment (annotated on failure as above).
5. Delete the keystore (always).
6. Copy the APK to `SignalBackup-<versionName>.apk`, run `apksigner verify --print-certs` (the
   certificate's SHA-1/SHA-256 also go to the step summary), and write `SHA256SUMS.txt`.
7. Attest build provenance for the APK (`actions/attest-build-provenance`).
8. Create the GitHub Release with auto-generated notes, attaching the APK and `SHA256SUMS.txt`. Tags
   containing `-` are marked as pre-releases.

### Secrets

Add these as secrets of the `release` environment (or as repository secrets):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` (macOS: `base64 -i release.jks`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Create a keystore once and keep it safe; losing it means users must uninstall to update:

```bash
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias signalbackup
```

Register the release key's SHA-1 as an Android OAuth client in the Google Cloud project (see
README), or Drive authorization will fail in release builds.

### Optional CI secret and variable

| Name | Kind | Value |
|---|---|---|
| `DEBUG_KEYSTORE_BASE64` | repository secret | `base64 -w0 ~/.android/debug.keystore` (macOS: `base64 -i …`). Uses the standard `android` / `androiddebugkey` / `android` credentials; override with `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`, `DEBUG_KEY_PASSWORD` env vars if your key differs. |
| `WEB_CLIENT_ID` | repository variable (Settings › Secrets and variables › Actions › Variables) | Web OAuth client ID. Empty or unset means the default in `app/build.gradle.kts`, which belongs to the maintainer's Cloud project. |

Using your own `~/.android/debug.keystore` means CI debug APKs and local debug builds share one
SHA-1, so one Android OAuth client covers both.

### Making a release

```bash
git tag v1.2.3
git push origin v1.2.3
```

Verify a downloaded APK:

```bash
sha256sum -c SHA256SUMS.txt
gh attestation verify SignalBackup-1.2.3.apk -R johnsonjsyuen/SignalBackup
```

## Signing config

`app/build.gradle.kts` creates the `release` signing config only when the `KEYSTORE_FILE`
environment variable is non-blank. Otherwise `assembleRelease` produces an unsigned APK, which is
what the CI build workflow relies on.

Likewise, when `DEBUG_KEYSTORE_FILE` is non-blank it creates a `ciDebug` signing config
(`DEBUG_KEYSTORE_PASSWORD` / `DEBUG_KEY_ALIAS` / `DEBUG_KEY_PASSWORD`, defaulting to
`android` / `androiddebugkey` / `android`) and uses it for the debug build type. Unset, debug builds
use AGP's default `~/.android/debug.keystore`.

## Dependabot

Weekly updates for:

- **Gradle** dependencies (version catalog), grouped as `kotlin-toolchain` (Kotlin + KSP, which must
  move together), `androidx`, and `test` (JUnit, MockK, Robolectric, Turbine); everything else gets its
  own PR.
- **GitHub Actions**, which updates the pinned SHAs.

Lint's dependency-freshness checks are disabled in `app/lint.xml` because Dependabot handles that.

## Troubleshooting

- **Release fails at "Derive version":** the tag isn't `vX.Y.Z[-suffix]`.
- **"KEYSTORE_BASE64 secret is not set":** the secret is missing from the `release` environment/repo.
- **`apksigner verify` fails / no signed APK found:** wrong passwords or alias; check the Gradle log
  artifact `release-gradle-logs`.
- **Tag pushed but no run:** the tag must start with `v` and be pushed explicitly (`git push origin <tag>`).
- **Lint fails CI:** read the annotations, or download `lint-reports`. Fix the issue or, if it's
  deliberate, configure it in `app/lint.xml`.

### Google sign-in fails silently

The account picker closes after you choose an account and nothing happens (logcat:
`[16] Account reauth failed`). Google doesn't recognise the APK's signing certificate.

- Register an **Android OAuth client** (package `com.johnsonyuen.signalbackup` + SHA-1) for every key
  that signs APKs you install: local debug key, the `DEBUG_KEYSTORE_BASE64` key, the release key, and
  the Play App Signing key. They must be in the **same Cloud project as the Web client ID** the APK
  was built with.
- OAuth consent screen: External; while in Testing, add your account as a test user.
- SHA-1 sources: the "signing certificate" section of the CI/release step summary,
  `./gradlew signingReport`, or `keytool -list -v -keystore <keystore>`.
- A CI `debug-apk` built without the `DEBUG_KEYSTORE_BASE64` secret (see the run's warning) is signed
  with a throwaway key and can never sign in; add the secret and rebuild.
- If you use your own Cloud project, set the `WEB_CLIENT_ID` variable.
- Diagnose on device: `adb logcat | grep -iE "credential|reauth"`.

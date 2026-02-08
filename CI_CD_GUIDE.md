# CI/CD Guide

This document explains the continuous integration and release pipeline for the Signal
Backup Android app. It covers both GitHub Actions workflows, how release signing works,
and how to set everything up from scratch.

---

## Table of Contents

1. [Overview](#overview)
2. [CI Build Workflow](#ci-build-workflow)
3. [Release Workflow](#release-workflow)
4. [Creating a Release Keystore](#creating-a-release-keystore)
5. [Configuring GitHub Secrets](#configuring-github-secrets)
6. [Making a Release](#making-a-release)
7. [How the Signing Config Works](#how-the-signing-config-works)
8. [Troubleshooting](#troubleshooting)

---

## Overview

The project uses two GitHub Actions workflows:

| Workflow | File | Purpose |
|---|---|---|
| **CI Build** | `.github/workflows/build.yml` | Builds the debug APK and runs lint on every push/PR to `main` |
| **Release** | `.github/workflows/release.yml` | Builds a signed release APK and publishes a GitHub Release when a `v*` tag is pushed |

Both workflows use Temurin JDK 20 and `gradle/actions/setup-gradle@v4` for Gradle
caching. The CI workflow needs no secrets. The Release workflow requires four secrets
containing the keystore and its credentials.

---

## CI Build Workflow

**File:** `.github/workflows/build.yml`

### Triggers

- **Push** to the `main` branch
- **Pull request** targeting the `main` branch

If multiple pushes happen in quick succession on the same branch, the `concurrency`
setting cancels the earlier in-progress run so only the latest commit is built.

### What It Does (Step by Step)

1. **Checkout repository** -- Clones the repo at the triggering commit.
2. **Set up JDK 20** -- Installs Temurin JDK 20 (required by the Android Gradle Plugin
   and Hilt annotation processors).
3. **Setup Gradle** -- Configures Gradle caching via `gradle/actions/setup-gradle@v4`
   so that subsequent builds reuse downloaded dependencies and build outputs.
4. **Build debug APK** -- Runs `./gradlew assembleDebug`. This compiles the Kotlin
   source, processes resources, and produces an unsigned debug APK.
5. **Run lint** -- Runs `./gradlew lint` to check for common Android issues (missing
   translations, deprecated API usage, accessibility problems, etc.).
6. **Upload debug APK** -- Uploads the debug APK as a build artifact, retained for 14
   days. You can download it from the workflow run's **Artifacts** section.

### Reading the Results

1. Go to the **Actions** tab in the GitHub repository.
2. Click on the **CI Build** workflow in the left sidebar.
3. Click a specific run to see the step-by-step logs.
4. Scroll to the bottom of the run summary to find the **debug-apk** artifact for
   download.

### Permissions

The workflow only requires `contents: read` -- it cannot push code or create releases.

---

## Release Workflow

**File:** `.github/workflows/release.yml`

### Trigger

- **Push** of a tag matching the pattern `v*` (e.g., `v1.0`, `v2.3.1`).

This workflow does **not** run on branch pushes or pull requests.

### What It Does (Step by Step)

1. **Checkout repository** -- Clones the repo at the tagged commit.
2. **Set up JDK 20** -- Installs Temurin JDK 20.
3. **Setup Gradle** -- Configures Gradle caching.
4. **Decode keystore** -- Takes the `KEYSTORE_BASE64` secret (a base64-encoded `.jks`
   file), decodes it, and writes it to a temporary file at
   `$RUNNER_TEMP/release.keystore`. This keeps the keystore out of the workspace and
   out of any artifact uploads.
5. **Build release APK** -- Runs `./gradlew assembleRelease` with four environment
   variables set:
   - `KEYSTORE_FILE` -- path to the decoded keystore
   - `KEYSTORE_PASSWORD` -- from the `KEYSTORE_PASSWORD` secret
   - `KEY_ALIAS` -- from the `KEY_ALIAS` secret
   - `KEY_PASSWORD` -- from the `KEY_PASSWORD` secret

   The Gradle build reads these environment variables in the `signingConfigs` block of
   `app/build.gradle.kts` to sign the APK. R8 minification and resource shrinking are
   also enabled for release builds.
6. **Create GitHub Release** -- Uses `softprops/action-gh-release@v2` to:
   - Create a GitHub Release named after the tag
   - Auto-generate release notes from merged PRs and commits since the last tag
   - Attach the signed release APK as a downloadable asset

### Permissions

The workflow requires `contents: write` so it can create the GitHub Release and upload
assets.

---

## Creating a Release Keystore

If you do not already have a keystore, generate one with the `keytool` command that
ships with the JDK.

```bash
keytool -genkeypair \
  -v \
  -keystore release.keystore \
  -alias signal-backup-key \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass YOUR_KEYSTORE_PASSWORD \
  -keypass YOUR_KEY_PASSWORD \
  -dname "CN=Your Name, OU=, O=, L=, S=, C=US"
```

**Parameter reference:**

| Parameter | Meaning |
|---|---|
| `-keystore release.keystore` | Output file name |
| `-alias signal-backup-key` | Name of the key entry inside the keystore |
| `-keyalg RSA -keysize 2048` | RSA algorithm with 2048-bit key |
| `-validity 10000` | Key valid for ~27 years |
| `-storepass` | Password to open the keystore file |
| `-keypass` | Password for this specific key alias |
| `-dname` | Distinguished name (fill in your details or leave the defaults) |

After running this command, you will have a `release.keystore` file. **Keep this file
safe and never commit it to version control.** If you lose it, you cannot sign updates
to an already-published app.

To verify the keystore was created correctly:

```bash
keytool -list -v -keystore release.keystore -storepass YOUR_KEYSTORE_PASSWORD
```

---

## Configuring GitHub Secrets

The release workflow reads four secrets from your repository settings. Here is how to
set them up.

### Step 1: Navigate to Repository Secrets

1. Open your repository on GitHub.
2. Click **Settings** (the gear icon in the top navigation bar).
3. In the left sidebar, expand **Secrets and variables** and click **Actions**.
4. Click **New repository secret** for each secret below.

### Step 2: Add Each Secret

You need to create exactly four secrets:

#### `KEYSTORE_BASE64`

The entire keystore file, base64-encoded. On macOS:

```bash
base64 -i release.keystore | pbcopy
```

This copies the encoded string to your clipboard. On Linux:

```bash
base64 -w 0 release.keystore | xclip -selection clipboard
```

Paste the clipboard contents as the secret value.

#### `KEYSTORE_PASSWORD`

The password you used for `-storepass` when creating the keystore. Enter the raw
password string (not base64-encoded).

#### `KEY_ALIAS`

The alias you used for `-alias` when creating the keystore (e.g., `signal-backup-key`).

#### `KEY_PASSWORD`

The password you used for `-keypass` when creating the keystore. Enter the raw password
string (not base64-encoded).

### Summary

| Secret Name | Value | Encoding |
|---|---|---|
| `KEYSTORE_BASE64` | Contents of `release.keystore` | Base64 |
| `KEYSTORE_PASSWORD` | Keystore store password | Plain text |
| `KEY_ALIAS` | Key alias name | Plain text |
| `KEY_PASSWORD` | Key entry password | Plain text |

---

## Making a Release

Once your secrets are configured, creating a release is a three-step process.

### Step 1: Tag the Commit

Make sure you are on the commit you want to release (usually the tip of `main`):

```bash
git checkout main
git pull origin main
```

Create an annotated tag:

```bash
git tag -a v1.0.0 -m "Release v1.0.0"
```

Use [semantic versioning](https://semver.org/) -- `vMAJOR.MINOR.PATCH`.

### Step 2: Push the Tag

```bash
git push origin v1.0.0
```

This triggers the Release workflow.

### Step 3: Watch the Workflow

1. Go to the **Actions** tab in your GitHub repository.
2. You should see a new **Release** workflow run triggered by the tag push.
3. Click into it to watch the build logs in real time.
4. When the workflow completes successfully, it creates a GitHub Release automatically.

### Step 4: Find the Release

1. Go to the **Releases** section on the right side of your repository's main page (or
   navigate to `https://github.com/YOUR_USER/YOUR_REPO/releases`).
2. The latest release will show the tag name, auto-generated release notes, and the
   signed APK as a downloadable asset.

### Pushing Multiple Tags

If you need to re-release (for example, after a hotfix):

```bash
git tag -a v1.0.1 -m "Hotfix release v1.0.1"
git push origin v1.0.1
```

Each tag push creates its own independent workflow run and GitHub Release.

---

## How the Signing Config Works

The signing configuration lives in `app/build.gradle.kts` inside the `android` block:

```kotlin
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
        // ...
        signingConfig = signingConfigs.getByName("release")
    }
}
```

### How It Works

1. Gradle evaluates `System.getenv("KEYSTORE_FILE")` at configuration time.
2. **If the environment variable is set** (as it is in CI), the four fields
   (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) are populated and the
   release APK is signed.
3. **If the environment variable is not set** (typical local development), the `if`
   block is skipped entirely. The signing config exists but has no values, which means
   `assembleRelease` produces an unsigned APK. This is harmless because local
   development uses `assembleDebug`, which automatically signs with the default debug
   keystore at `~/.android/debug.keystore`.

### Why This Design

- **No secrets in version control.** The keystore file and passwords never appear in
  the repository.
- **No-op locally.** Developers do not need to set up any environment variables to
  build debug APKs.
- **CI-friendly.** The release workflow injects the environment variables, and the
  same `build.gradle.kts` works in both contexts without conditional logic or
  build flavors.

---

## Troubleshooting

### Missing Secrets

**Symptom:** The Release workflow fails at the "Decode keystore" or "Build release APK"
step.

**Fix:** Verify that all four secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`) are set in **Settings > Secrets and variables > Actions**.
Secret names are case-sensitive.

---

### Wrong JDK Version

**Symptom:** Build fails with errors about unsupported class file version, or
`jlink`-related errors.

**Fix:** Both workflows pin Temurin JDK 20. If you are building locally, make sure your
`JAVA_HOME` points to a compatible JDK. GraalCE 21 is known to cause `jlink` errors
with Android builds -- use Temurin JDK 20 instead. You can set this in
`gradle.properties`:

```properties
org.gradle.java.home=/path/to/temurin-jdk-20
```

---

### Keystore Decode Failure

**Symptom:** The "Decode keystore" step fails or produces a corrupted file.

**Fix:** The `KEYSTORE_BASE64` secret must be a single continuous base64 string with no
line breaks. Re-encode the keystore:

```bash
# macOS
base64 -i release.keystore | tr -d '\n' | pbcopy

# Linux
base64 -w 0 release.keystore | xclip -selection clipboard
```

Paste the result as the secret value, replacing the old one.

---

### Unsigned Release APK

**Symptom:** `assembleRelease` succeeds but the APK is unsigned or cannot be installed
on a device ("app not installed" error).

**Cause:** The `KEYSTORE_FILE` environment variable was not set, so the signing config
was a no-op.

**Fix:** Make sure the Release workflow is setting all four environment variables in the
"Build release APK" step. If building locally for testing, export the variables first:

```bash
export KEYSTORE_FILE=/path/to/release.keystore
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=signal-backup-key
export KEY_PASSWORD=your_key_password
./gradlew assembleRelease
```

---

### Tag Does Not Trigger the Release Workflow

**Symptom:** You pushed a tag but no workflow run appeared.

**Fix:** The tag must match the pattern `v*`. Tags like `release-1.0` or `1.0.0`
(without the `v` prefix) will not trigger the workflow. Create a conforming tag:

```bash
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```

Also verify that the workflow file exists on the default branch (`main`). GitHub Actions
only recognizes workflow files that are present on the default branch.

---

### Lint Failures Block the CI Build

**Symptom:** The CI Build workflow fails at the "Run lint" step.

**Fix:** Lint runs after the debug APK is built, so the APK artifact is still uploaded
even if lint fails. To fix lint issues locally:

```bash
./gradlew lint
```

Open the generated report at `app/build/reports/lint-results-debug.html` in a browser
to see detailed explanations and suggested fixes.

---

### Gradle Cache Issues

**Symptom:** The build fails with cryptic errors that do not reproduce locally, or
dependencies fail to resolve.

**Fix:** The `gradle/actions/setup-gradle@v4` action manages caching automatically. To
force a clean build, you can re-run the workflow with the **Re-run all jobs** button in
the Actions UI. If the problem persists, check that `gradle/libs.versions.toml` and
`settings.gradle.kts` are correct on the branch or tag being built.

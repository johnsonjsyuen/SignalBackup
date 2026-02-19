/**
 * HomeScreen.kt - The main screen of the app, showing upload status and controls.
 *
 * This screen is the first thing the user sees. It displays:
 * 1. A [StatusCard] showing the current upload status (idle, uploading, success, failed).
 * 2. A [CountdownTimer] showing time until the next scheduled upload.
 * 3. Quick-info chips for local folder and Drive folder selection status.
 * 4. A "Sign In with Google" button (if not signed in) or "Upload Now" button (if signed in).
 *
 * Google Sign-In flow (implemented here, not in ViewModel):
 * The sign-in flow uses the Credential Manager API, which is suspend-based and does not
 * require Activity result launchers. Here is the step-by-step:
 * 1. User taps "Sign In with Google" -> requestSignIn() calls CredentialManager.getCredential()
 *    with a GetSignInWithGoogleOption request, showing a bottom sheet account picker.
 * 2. On success, the email is extracted from GoogleIdTokenCredential and saved via
 *    viewModel.setGoogleAccountEmail().
 * 3. On subsequent uploads, if Google needs OAuth consent for Drive access, the upload
 *    use case returns NeedsConsent with a consent Intent.
 * 4. The LaunchedEffect watching uploadStatus detects NeedsConsent and launches
 *    consentLauncher with the consent Intent.
 * 5. If consent is granted, uploadNow() is called again to retry the upload.
 *
 * Key Compose concepts:
 * - **collectAsStateWithLifecycle()**: Collects a Flow as Compose State in a lifecycle-aware
 *   way. Stops collection when the UI is not visible (saves resources).
 * - **hiltViewModel()**: Creates or retrieves the Hilt-injected ViewModel for this composable.
 * - **rememberCoroutineScope()**: Creates a coroutine scope tied to the composable's lifecycle.
 *   Used to launch the suspend-based Credential Manager sign-in call.
 * - **LaunchedEffect(uploadStatus)**: Runs a side-effect whenever the upload status changes.
 *   Used to detect NeedsConsent and launch the consent Intent automatically.
 * - **LocalContext.current**: Accesses the current Activity context from within a composable.
 *   Needed for CredentialManager which requires an Activity context.
 *
 * @see ui.screen.home.HomeViewModel for the state management
 * @see ui.component.StatusCard for the upload status display
 * @see ui.component.CountdownTimer for the countdown display
 */
package com.johnsonyuen.signalbackup.ui.screen.home

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.ui.component.CountdownTimer
import com.johnsonyuen.signalbackup.ui.component.StatusCard
import com.johnsonyuen.signalbackup.ui.component.UploadProgressCard

private const val TAG = "HomeScreen"
private const val WEB_CLIENT_ID = "708439544712-sfhh5anisbh3mik9tt1l638r86c7mcvp.apps.googleusercontent.com"

/**
 * The Home screen composable -- the app's primary interface.
 *
 * @param onNavigateToSettings Callback to programmatically switch to the Settings tab.
 *        Called when the user taps the folder info chips.
 * @param viewModel The Hilt-injected ViewModel providing state and actions.
 */
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    // Collect all ViewModel state flows as Compose State.
    // collectAsStateWithLifecycle() automatically pauses collection when the
    // screen is in the background, preventing unnecessary work.
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val googleEmail by viewModel.googleAccountEmail.collectAsStateWithLifecycle()
    val scheduleHour by viewModel.scheduleHour.collectAsStateWithLifecycle()
    val scheduleMinute by viewModel.scheduleMinute.collectAsStateWithLifecycle()
    val localFolderUri by viewModel.localFolderUri.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var signInError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // -----------------------------------------------------------------------
    // Activity result launcher for Drive consent.
    // This is the Compose equivalent of the old onActivityResult() pattern.
    // -----------------------------------------------------------------------

    /**
     * Launcher for the Drive OAuth consent dialog.
     * If the user grants consent (RESULT_OK), retry the upload.
     * If denied, set the upload status to Failed.
     */
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Drive consent granted, retrying upload")
            viewModel.uploadNow()
        } else {
            Log.w(TAG, "Drive consent denied, resultCode=${result.resultCode}")
            viewModel.setUploadFailed("Drive permission denied")
        }
    }

    /**
     * Side-effect that watches the upload status. When it becomes NeedsConsent,
     * automatically launch the consent Intent provided by the use case.
     * This is a "fire-and-forget" effect -- it runs once per status change.
     */
    LaunchedEffect(uploadStatus) {
        if (uploadStatus is UploadStatus.NeedsConsent) {
            consentLauncher.launch((uploadStatus as UploadStatus.NeedsConsent).consentIntent)
        }
    }

    /**
     * Launches the Credential Manager sign-in flow.
     *
     * Uses GetSignInWithGoogleOption to show a bottom-sheet account picker.
     * This replaces the deprecated GoogleSignIn API. Only authentication (email)
     * is handled here — Drive scope authorization happens just-in-time on
     * first upload via the existing NeedsConsent → consentLauncher flow.
     */
    fun requestSignIn() {
        signInError = null
        coroutineScope.launch {
            try {
                val signInOption = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()
                val credentialManager = CredentialManager.create(context)
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                val idTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val email = idTokenCredential.id
                Log.d(TAG, "Sign-in result: email=$email")
                if (email != null) {
                    viewModel.setGoogleAccountEmail(email)
                    signInError = null
                } else {
                    signInError = "Sign-in succeeded but no email returned"
                }
            } catch (e: NoCredentialException) {
                Log.e(TAG, "No credentials available", e)
                signInError = "No Google accounts found on this device"
            } catch (e: GetCredentialCancellationException) {
                Log.d(TAG, "Sign-in cancelled by user")
                // User cancelled -- no error to show.
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(TAG, "Failed to parse Google ID token", e)
                signInError = "Sign-in failed: invalid response"
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Sign-in failed: ${e.message}", e)
                signInError = "Sign-in failed: ${e.message}"
            }
        }
    }

    // -----------------------------------------------------------------------
    // UI Layout
    // -----------------------------------------------------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Upload progress card -- shown when the upload is actively running and the
        // worker has reported progress data. Before the first progress callback, the
        // regular StatusCard with an indeterminate spinner is shown instead.
        val currentProgress = uploadProgress
        if (uploadStatus is UploadStatus.Uploading && currentProgress != null) {
            UploadProgressCard(progress = currentProgress)
        } else {
            // Upload status card -- shows idle/uploading/success/failed/needs-consent.
            StatusCard(status = uploadStatus)
        }

        // Countdown timer -- shows time remaining until next scheduled upload.
        CountdownTimer(scheduleHour = scheduleHour, scheduleMinute = scheduleMinute)

        // Quick-info chips showing whether local and Drive folders are configured.
        // Tapping either chip navigates to the Settings tab for setup.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onNavigateToSettings,
                label = {
                    Text(
                        if (localFolderUri != null) "Local folder set" else "No local folder"
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            AssistChip(
                onClick = onNavigateToSettings,
                label = { Text(driveFolderName ?: "No Drive folder") },
                leadingIcon = {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        // Push the sign-in/upload button to the bottom of the screen.
        Spacer(modifier = Modifier.weight(1f))

        // Show sign-in error if the last sign-in attempt failed.
        if (signInError != null) {
            Text(
                text = signInError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        // Two states: signed in (show email + upload button) vs. not signed in (show sign-in).
        if (googleEmail != null) {
            Text(
                text = "Signed in as $googleEmail",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // "Upload Now" button -- disabled while an upload is already in progress.
            Button(
                onClick = { viewModel.uploadNow() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uploadStatus !is UploadStatus.Uploading
            ) {
                Icon(
                    Icons.Default.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Upload Now")
            }

            // "Cancel Upload" button -- only visible while an upload is in progress.
            if (uploadStatus is UploadStatus.Uploading) {
                OutlinedButton(
                    onClick = { viewModel.cancelUpload() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel Upload")
                }
            }
        } else {
            // Google Sign-In button -- initiates the OAuth flow.
            Button(
                onClick = { requestSignIn() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign In with Google")
            }
        }
    }
}

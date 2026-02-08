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
 * The sign-in flow involves launching Android Intents and receiving results, which is
 * inherently tied to the Activity/UI layer. Here is the step-by-step:
 * 1. User taps "Sign In with Google" -> requestSignIn() builds GoogleSignInOptions
 *    with email and DRIVE scope, then launches the sign-in Intent.
 * 2. signInLauncher receives the result -> extracts the email from the signed-in account.
 * 3. If successful, the email is saved to DataStore via viewModel.setGoogleAccountEmail().
 * 4. On subsequent uploads, if Google needs OAuth consent for Drive access, the upload
 *    use case returns NeedsConsent with a consent Intent.
 * 5. The LaunchedEffect watching uploadStatus detects NeedsConsent and launches
 *    consentLauncher with the consent Intent.
 * 6. If consent is granted, uploadNow() is called again to retry the upload.
 *
 * Key Compose concepts:
 * - **collectAsStateWithLifecycle()**: Collects a Flow as Compose State in a lifecycle-aware
 *   way. Stops collection when the UI is not visible (saves resources).
 * - **hiltViewModel()**: Creates or retrieves the Hilt-injected ViewModel for this composable.
 * - **rememberLauncherForActivityResult()**: Creates a launcher for starting Activities
 *   and receiving their results (the Compose equivalent of onActivityResult).
 * - **LaunchedEffect(uploadStatus)**: Runs a side-effect whenever the upload status changes.
 *   Used to detect NeedsConsent and launch the consent Intent automatically.
 * - **LocalContext.current**: Accesses the current Activity context from within a composable.
 *   Needed for GoogleSignIn.getClient() which requires an Activity context.
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.ui.component.CountdownTimer
import com.johnsonyuen.signalbackup.ui.component.StatusCard
import com.johnsonyuen.signalbackup.ui.component.UploadProgressCard

private const val TAG = "HomeScreen"

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

    // -----------------------------------------------------------------------
    // Activity result launchers for Google Sign-In and Drive consent.
    // These are the Compose equivalents of the old onActivityResult() pattern.
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
     * Launcher for the Google Sign-In flow.
     * On success, extracts the email and saves it to DataStore.
     * On failure, shows an error message on screen.
     */
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val email = account?.email
            Log.d(TAG, "Sign-in result: email=$email, account=$account")
            if (email != null) {
                viewModel.setGoogleAccountEmail(email)
                signInError = null
            } else {
                signInError = "Sign-in succeeded but no email returned"
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: statusCode=${e.statusCode}, message=${e.message}", e)
            signInError = "Sign-in failed (code ${e.statusCode})"
        }
    }

    /**
     * Builds GoogleSignInOptions and launches the sign-in Intent.
     *
     * GoogleSignInOptions.DEFAULT_SIGN_IN provides basic profile info.
     * requestEmail() adds the email scope.
     * requestScopes(DRIVE) requests full Drive access so the app can both
     * upload files AND list/browse existing user folders in the folder picker.
     * The narrower DRIVE_FILE scope only sees app-created files/folders.
     *
     * Note: GoogleSignIn is deprecated but still functional. The newer
     * Credential Manager API does not yet support Drive scopes directly.
     */
    fun requestSignIn() {
        signInError = null
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        signInLauncher.launch(client.signInIntent)
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

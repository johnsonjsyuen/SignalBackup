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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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

private const val TAG = "HomeScreen"

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val googleEmail by viewModel.googleAccountEmail.collectAsStateWithLifecycle()
    val scheduleHour by viewModel.scheduleHour.collectAsStateWithLifecycle()
    val scheduleMinute by viewModel.scheduleMinute.collectAsStateWithLifecycle()
    val localFolderUri by viewModel.localFolderUri.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var signInError by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(uploadStatus) {
        if (uploadStatus is UploadStatus.NeedsConsent) {
            consentLauncher.launch((uploadStatus as UploadStatus.NeedsConsent).consentIntent)
        }
    }

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

    fun requestSignIn() {
        signInError = null
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        signInLauncher.launch(client.signInIntent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        StatusCard(status = uploadStatus)

        CountdownTimer(scheduleHour = scheduleHour, scheduleMinute = scheduleMinute)

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

        Spacer(modifier = Modifier.weight(1f))

        if (signInError != null) {
            Text(
                text = signInError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        if (googleEmail != null) {
            Text(
                text = "Signed in as $googleEmail",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

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
        } else {
            Button(
                onClick = { requestSignIn() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign In with Google")
            }
        }
    }
}

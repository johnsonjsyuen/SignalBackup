package com.johnsonyuen.signalbackup.ui.screen.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.ui.component.CountdownTimer
import com.johnsonyuen.signalbackup.ui.component.StatusCard

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val googleEmail by viewModel.googleAccountEmail.collectAsStateWithLifecycle()
    val scheduleHour by viewModel.scheduleHour.collectAsStateWithLifecycle()
    val scheduleMinute by viewModel.scheduleMinute.collectAsStateWithLifecycle()
    val localFolderUri by viewModel.localFolderUri.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            account?.email?.let { email ->
                viewModel.setGoogleAccountEmail(email)
            }
        }
    }

    fun requestAuthorization() {
        val authRequest = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(authRequest)
            .addOnSuccessListener { authorizationResult: AuthorizationResult ->
                if (authorizationResult.hasResolution()) {
                    val pendingIntent = authorizationResult.pendingIntent
                    if (pendingIntent != null) {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }
                } else {
                    // Already authorized -- retrieve account from last sign-in
                    val account = GoogleSignIn.getLastSignedInAccount(context)
                    account?.email?.let { email ->
                        viewModel.setGoogleAccountEmail(email)
                    }
                }
            }
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
                onClick = {},
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
                onClick = {},
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

        if (googleEmail != null) {
            Text(
                text = "Signed in as $googleEmail",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Button(
                onClick = { viewModel.uploadNow(context) },
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
                onClick = { requestAuthorization() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign In with Google")
            }
        }
    }
}

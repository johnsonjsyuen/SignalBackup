package com.johnsonyuen.signalbackup.ui.screen.home

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.johnsonyuen.signalbackup.BuildConfig
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import com.johnsonyuen.signalbackup.ui.common.UiText
import com.johnsonyuen.signalbackup.ui.common.resolve
import com.johnsonyuen.signalbackup.ui.component.CleanUpDialog
import com.johnsonyuen.signalbackup.ui.component.LastResultCard
import com.johnsonyuen.signalbackup.ui.component.LastSuccessText
import com.johnsonyuen.signalbackup.ui.component.NextRunCard
import com.johnsonyuen.signalbackup.ui.component.NoBackupsWarning
import com.johnsonyuen.signalbackup.ui.component.SetupChecklistCard
import com.johnsonyuen.signalbackup.ui.component.UploadRunCard
import kotlinx.coroutines.launch

private const val TAG = "HomeScreen"

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // The ViewModel remembers which action is waiting on consent, so this survives rotation.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.data) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.LaunchConsent -> {
                    try {
                        consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not launch consent screen: ${e.javaClass.simpleName}")
                        viewModel.onConsentLaunchFailed()
                    }
                }
                is HomeEvent.ShowMessage -> {
                    val text = event.text.resolve(context)
                    launch { snackbarHostState.showSnackbar(text) }
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshLocalFolder() }

    val signIn: () -> Unit = {
        scope.launch {
            signInWithGoogle(context)?.let { outcome ->
                when (outcome) {
                    is SignInOutcome.Success -> viewModel.onSignedIn(outcome.email)
                    is SignInOutcome.Error -> viewModel.onSignInError(outcome.message)
                }
            }
        }
    }

    CleanUpDialog(
        state = state.cleanUp,
        driveFolderName = state.driveFolderName,
        accountEmail = state.accountEmail,
        onConfirm = viewModel::confirmCleanUp,
        onRetry = viewModel::openCleanUp,
        onDismiss = viewModel::dismissCleanUp,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The outer navigation Scaffold already applies system-bar insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.loading && !state.setup.isComplete) {
                SetupChecklistCard(
                    setup = state.setup,
                    authorizing = state.authorizing,
                    onSignIn = signIn,
                    onGrantAccess = viewModel::grantDriveAccess,
                    onChooseFolder = onNavigateToSettings,
                )
            }

            UploadRunCard(run = state.run)

            if (state.run is UploadRunState.Idle) {
                state.lastResult?.let { result ->
                    LastResultCard(
                        result = result,
                        onFix = { fix ->
                            when (fix) {
                                ResultFix.SIGN_IN -> signIn()
                                ResultFix.CHOOSE_FOLDER -> onNavigateToSettings()
                                else -> viewModel.onFix(fix)
                            }
                        },
                    )
                }
            }

            if (state.setup.signedIn) {
                LastSuccessText(ageMillis = state.lastSuccessAgeMillis, overdue = state.isBackupOverdue)
            }

            if (state.setup.isComplete) {
                NextRunCard(
                    nextRunMillis = state.nextRunMillis,
                    nowMillis = state.nowMillis,
                    scheduleEnabled = state.scheduleEnabled,
                )
                if (state.setup.localFolderHasBackups == false) NoBackupsWarning()
                FolderChips(
                    localFolderName = state.setup.localFolderName,
                    driveFolderName = state.driveFolderName,
                    onClick = onNavigateToSettings,
                )
            }

            state.accountEmail?.let {
                Text(stringResource(R.string.signed_in_as, it), style = MaterialTheme.typography.bodySmall)
            }

            if (state.isUploadActive) {
                OutlinedButton(onClick = viewModel::cancelUpload, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(Icons.Default.Close, stringResource(R.string.action_cancel_upload))
                }
            } else {
                Button(
                    onClick = viewModel::uploadNow,
                    enabled = state.canUploadNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonContent(Icons.Default.CloudUpload, stringResource(R.string.action_upload_now))
                }
                if (!state.setup.isComplete) {
                    Text(
                        stringResource(R.string.upload_disabled_setup),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.canCleanUp) {
                    OutlinedButton(onClick = viewModel::openCleanUp, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(Icons.Default.DeleteSweep, stringResource(R.string.action_clean_up))
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonContent(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(modifier = Modifier.width(8.dp))
    Text(label)
}

@Composable
private fun FolderChips(localFolderName: String?, driveFolderName: String?, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = onClick,
            label = { Text(localFolderName ?: stringResource(R.string.local_folder_label)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = stringResource(R.string.cd_local_folder),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        AssistChip(
            onClick = onClick,
            label = { Text(driveFolderName ?: stringResource(R.string.not_set)) },
            leadingIcon = {
                Icon(
                    Icons.Default.CloudQueue,
                    contentDescription = stringResource(R.string.cd_drive_folder),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

private sealed interface SignInOutcome {
    data class Success(val email: String) : SignInOutcome
    data class Error(val message: UiText) : SignInOutcome
}

/** Credential Manager "Sign in with Google". Returns null when the user cancelled. */
private suspend fun signInWithGoogle(context: Context): SignInOutcome? = try {
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val result = CredentialManager.create(context).getCredential(context, request)
    val email = GoogleIdTokenCredential.createFrom(result.credential.data).id
    if (email.isNotBlank()) SignInOutcome.Success(email)
    else SignInOutcome.Error(UiText.Res(R.string.sign_in_no_email))
} catch (e: GetCredentialCancellationException) {
    null
} catch (e: NoCredentialException) {
    Log.w(TAG, "No Google accounts available")
    SignInOutcome.Error(UiText.Res(R.string.sign_in_no_accounts))
} catch (e: GoogleIdTokenParsingException) {
    Log.w(TAG, "Invalid Google ID token response")
    SignInOutcome.Error(UiText.Res(R.string.sign_in_failed))
} catch (e: GetCredentialException) {
    Log.w(TAG, "Sign-in failed: ${e.javaClass.simpleName}")
    SignInOutcome.Error(UiText.Res(R.string.sign_in_failed))
}

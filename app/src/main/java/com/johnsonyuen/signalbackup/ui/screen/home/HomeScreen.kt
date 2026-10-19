package com.johnsonyuen.signalbackup.ui.screen.home

import android.util.Log
import androidx.activity.compose.LocalActivity
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
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
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // The ViewModel remembers which action is waiting on consent, so this survives rotation.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        Log.d(TAG, "Consent result: resultCode=${result.resultCode}")
        viewModel.onConsentResult(result.data)
    }

    // Only handle events while RESUMED: launching the consent screen right after the Credential
    // Manager sheet closes (before the activity is resumed) can be dropped silently. The events
    // channel is buffered, so events wait until the screen is resumed.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { event ->
                when (event) {
                    is HomeEvent.LaunchConsent -> {
                        try {
                            consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent).build())
                            viewModel.onConsentLaunched()
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not launch consent screen: ${e.javaClass.simpleName}")
                            viewModel.onConsentLaunchFailed()
                        }
                    }
                    is HomeEvent.ShowMessage -> {
                        val text = event.text.resolve(context)
                        // Screen-wide scope so pausing doesn't dismiss the snackbar mid-show.
                        scope.launch { snackbarHostState.showSnackbar(text) }
                    }
                }
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onPaused() }

    val signIn: () -> Unit = {
        if (activity != null) {
            viewModel.signIn(activity)
        } else {
            Log.w(TAG, "No Activity available for sign-in")
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
                    signingIn = state.signingIn,
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

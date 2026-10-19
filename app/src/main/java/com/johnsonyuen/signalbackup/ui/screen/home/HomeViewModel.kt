package com.johnsonyuen.signalbackup.ui.screen.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.GcScanResult
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.domain.usecase.GarbageCollectUseCase
import com.johnsonyuen.signalbackup.domain.usecase.ManualUploadUseCase
import com.johnsonyuen.signalbackup.ui.common.LocalFolderAccess
import com.johnsonyuen.signalbackup.ui.common.LocalFolderInfo
import com.johnsonyuen.signalbackup.ui.common.MinuteTicker
import com.johnsonyuen.signalbackup.ui.common.UiText
import com.johnsonyuen.signalbackup.ui.consent.AuthorizationStart
import com.johnsonyuen.signalbackup.ui.consent.ConsentAction
import com.johnsonyuen.signalbackup.ui.consent.DriveAuthorizationFlow
import com.johnsonyuen.signalbackup.ui.navigation.FixAction
import com.johnsonyuen.signalbackup.ui.navigation.FixActionBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home screen: current run, last result, setup checklist, consent and Clean Up. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    historyRepository: UploadHistoryRepository,
    private val uploadScheduler: UploadScheduler,
    private val manualUpload: ManualUploadUseCase,
    private val garbageCollect: GarbageCollectUseCase,
    private val authorizationFlow: DriveAuthorizationFlow,
    private val localFolderAccess: LocalFolderAccess,
    private val fixActionBus: FixActionBus,
    ticker: MinuteTicker,
) : ViewModel() {

    private data class LocalState(
        val authorizing: Boolean = false,
        val authorizedThisSession: Boolean = false,
        val cleanUp: CleanUpState = CleanUpState.Hidden,
    )

    private val local = MutableStateFlow(LocalState())
    private val folderRefresh = MutableStateFlow(0)
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)

    /** One-shot events (consent launch, messages). */
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private var pendingConsentAction: ConsentAction? = null
    private var scanJob: Job? = null

    private val folderInfo: Flow<LocalFolderInfo?> =
        combine(
            settingsRepository.settings.map { it.localFolderUri }.distinctUntilChanged(),
            folderRefresh,
        ) { uri, _ -> uri }
            .transformLatest { uri -> emit(uri?.let { localFolderAccess.inspect(it) }) }
            .onStart { emit(null) }

    private val historyFlow: Flow<Pair<UploadRecord?, UploadRecord?>> =
        combine(historyRepository.getLatest(), historyRepository.getLatestSuccess()) { a, b -> a to b }

    private val runFlow: Flow<Pair<UploadRunState, Long?>> =
        combine(
            uploadScheduler.observeRunState(),
            uploadScheduler.observeNextScheduledRunMillis(),
        ) { run, next -> run to next }

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        historyFlow,
        runFlow,
        combine(folderInfo, ticker.ticks()) { info, now -> info to now },
        local,
    ) { settings, (latest, latestSuccess), (run, nextRun), (info, now), localState ->
        buildState(settings, latest, latestSuccess, run, nextRun, info, now, localState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            fixActionBus.pending.filter { it == FixAction.CONSENT }.collect {
                if (fixActionBus.consume(FixAction.CONSENT)) authorize(ConsentAction.UPLOAD)
            }
        }
    }

    private fun buildState(
        settings: AppSettings,
        latest: UploadRecord?,
        latestSuccess: UploadRecord?,
        run: UploadRunState,
        nextRun: Long?,
        info: LocalFolderInfo?,
        now: Long,
        localState: LocalState,
    ): HomeUiState {
        val signedIn = settings.googleAccountEmail != null
        val consentMissing = latest?.failureReason == FailureReason.NEEDS_CONSENT &&
            !localState.authorizedThisSession
        val folderSet = settings.localFolderUri != null
        return HomeUiState(
            loading = false,
            accountEmail = settings.googleAccountEmail,
            driveFolderName = settings.driveFolderName,
            setup = SetupChecklist(
                signedIn = signedIn,
                driveAuthorized = signedIn && settings.driveFolderId != null && !consentMissing,
                localFolderSet = folderSet,
                localFolderName = if (folderSet) info?.name else null,
                localFolderAccessible = !folderSet || info == null || info.accessible,
                localFolderHasBackups = if (folderSet) info?.hasSignalBackups else null,
            ),
            run = run,
            lastResult = latest?.toLastResultUi(),
            lastSuccessMillis = latestSuccess?.timestamp?.toEpochMilli(),
            nowMillis = now,
            nextRunMillis = if (settings.scheduleEnabled) nextRun else null,
            scheduleEnabled = settings.scheduleEnabled,
            authorizing = localState.authorizing,
            cleanUp = localState.cleanUp,
        )
    }

    /** Re-reads the local folder (e.g. when the screen resumes). */
    fun refreshLocalFolder() {
        folderRefresh.update { it + 1 }
    }

    fun uploadNow() {
        if (!uiState.value.canUploadNow) return
        viewModelScope.launch { manualUpload() }
    }

    fun cancelUpload() {
        uploadScheduler.cancelRunningUploads()
    }

    /** Called after Credential Manager sign-in succeeds. */
    fun onSignedIn(email: String) {
        viewModelScope.launch {
            settingsRepository.setGoogleAccountEmail(email)
            authorize(ConsentAction.RESOLVE_FOLDER)
        }
    }

    /** Starts Drive authorization for the setup checklist. */
    fun grantDriveAccess() {
        viewModelScope.launch { authorize(ConsentAction.RESOLVE_FOLDER) }
    }

    /** Runs the fix offered on the last-result card (sign-in and folder choice are handled by the screen). */
    fun onFix(fix: ResultFix) {
        when (fix) {
            ResultFix.GRANT_ACCESS, ResultFix.FIX_DRIVE_FOLDER ->
                viewModelScope.launch { authorize(ConsentAction.UPLOAD) }
            ResultFix.RETRY -> uploadNow()
            ResultFix.CLEAN_UP -> openCleanUp()
            ResultFix.SIGN_IN, ResultFix.CHOOSE_FOLDER -> Unit
        }
    }

    private suspend fun authorize(action: ConsentAction) {
        if (local.value.authorizing) return
        local.update { it.copy(authorizing = true) }
        when (val start = authorizationFlow.start()) {
            is AuthorizationStart.Authorized -> {
                local.update { it.copy(authorizing = false, authorizedThisSession = true) }
                start.message?.let { _events.send(HomeEvent.ShowMessage(it)) }
                resume(action)
            }
            is AuthorizationStart.NeedsUi -> {
                pendingConsentAction = action
                _events.send(HomeEvent.LaunchConsent(start.pendingIntent, action))
            }
            is AuthorizationStart.Failed -> {
                local.update { it.copy(authorizing = false) }
                _events.send(HomeEvent.ShowMessage(start.message))
            }
        }
    }

    /** Result of the consent screen launched for [action]. */
    fun onConsentResult(action: ConsentAction?, data: Intent?) {
        val resumeAction = action ?: pendingConsentAction ?: ConsentAction.RESOLVE_FOLDER
        pendingConsentAction = null
        viewModelScope.launch {
            val result = authorizationFlow.complete(data)
            local.update {
                it.copy(authorizing = false, authorizedThisSession = it.authorizedThisSession || result.granted)
            }
            result.message?.let { _events.send(HomeEvent.ShowMessage(it)) }
            if (result.granted) resume(resumeAction)
        }
    }

    /** The consent screen could not be launched. */
    fun onConsentLaunchFailed() {
        pendingConsentAction = null
        local.update { it.copy(authorizing = false) }
        viewModelScope.launch {
            _events.send(HomeEvent.ShowMessage(UiText.Res(R.string.error_consent_launch)))
        }
    }

    private suspend fun resume(action: ConsentAction) {
        when (action) {
            ConsentAction.UPLOAD -> manualUpload()
            ConsentAction.CLEAN_UP -> openCleanUp()
            ConsentAction.RESOLVE_FOLDER, ConsentAction.FOLDER_PICKER -> Unit
        }
    }

    fun onSignInError(message: UiText) {
        viewModelScope.launch { _events.send(HomeEvent.ShowMessage(message)) }
    }

    // --- Clean Up ---

    fun openCleanUp() {
        scanJob?.cancel()
        setCleanUp(CleanUpState.Scanning)
        scanJob = viewModelScope.launch {
            try {
                val next = when (val result = garbageCollect.scan()) {
                    GcScanResult.NothingToClean -> CleanUpState.NothingToClean
                    is GcScanResult.Blocked -> CleanUpState.Blocked(result.message)
                    is GcScanResult.Ready ->
                        if (result.toTrash.isEmpty()) CleanUpState.NothingToClean
                        else CleanUpState.Ready(result.kept, result.toTrash)
                }
                setCleanUp(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConsentException) {
                setCleanUp(CleanUpState.Hidden)
                authorize(ConsentAction.CLEAN_UP)
            } catch (e: Exception) {
                setCleanUp(CleanUpState.Error(DriveAuthorizationFlow.errorText(e)))
            }
        }
    }

    fun confirmCleanUp() {
        val ready = local.value.cleanUp as? CleanUpState.Ready ?: return
        setCleanUp(CleanUpState.Trashing(ready.toTrash.size))
        viewModelScope.launch {
            try {
                setCleanUp(CleanUpState.Done(garbageCollect.trash(ready.toTrash)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConsentException) {
                setCleanUp(CleanUpState.Hidden)
                authorize(ConsentAction.CLEAN_UP)
            } catch (e: Exception) {
                setCleanUp(CleanUpState.Error(DriveAuthorizationFlow.errorText(e)))
            }
        }
    }

    /** Closes the dialog and cancels a running scan. Ignored while items are being trashed. */
    fun dismissCleanUp() {
        if (local.value.cleanUp is CleanUpState.Trashing) return
        scanJob?.cancel()
        scanJob = null
        setCleanUp(CleanUpState.Hidden)
    }

    private fun setCleanUp(state: CleanUpState) {
        local.update { it.copy(cleanUp = state) }
    }
}

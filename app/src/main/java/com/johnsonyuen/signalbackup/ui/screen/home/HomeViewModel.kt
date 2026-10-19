package com.johnsonyuen.signalbackup.ui.screen.home

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.data.auth.GoogleAccountSignIn
import com.johnsonyuen.signalbackup.data.auth.GoogleSignInResult
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
    private val googleSignIn: GoogleAccountSignIn,
    ticker: MinuteTicker,
) : ViewModel() {

    private data class LocalState(
        val authorizing: Boolean = false,
        val signingIn: Boolean = false,
        val authorizedThisSession: Boolean = false,
        val cleanUp: CleanUpState = CleanUpState.Hidden,
    )

    private val local = MutableStateFlow(LocalState())
    private val folderRefresh = MutableStateFlow(0)
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)

    /** One-shot events (consent launch, messages). */
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private var pendingConsentAction: ConsentAction? = null
    /** The consent screen was launched and its result hasn't arrived yet. */
    private var consentLaunched = false
    /** The screen paused after [consentLaunched], i.e. the consent screen actually took over. */
    private var pausedSinceConsentLaunch = false
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
            signingIn = localState.signingIn,
            cleanUp = localState.cleanUp,
        )
    }

    /** Re-reads the local folder (e.g. when the screen resumes). */
    fun refreshLocalFolder() {
        folderRefresh.update { it + 1 }
    }

    /** The screen launched the consent screen for the last [HomeEvent.LaunchConsent]. */
    fun onConsentLaunched() {
        consentLaunched = true
        pausedSinceConsentLaunch = false
    }

    /** ON_PAUSE of the Home screen. */
    fun onPaused() {
        if (consentLaunched) pausedSinceConsentLaunch = true
    }

    /**
     * ON_RESUME of the Home screen. The consent result is delivered before ON_RESUME, so if the
     * consent screen was launched, took over the screen, and we are back without a result, the
     * result was lost: clear the `authorizing` latch so the user can try again.
     */
    fun onResumed() {
        refreshLocalFolder()
        if (consentLaunched && pausedSinceConsentLaunch) {
            consentLaunched = false
            pausedSinceConsentLaunch = false
            if (pendingConsentAction != null) onConsentAbandoned()
        }
    }

    private fun onConsentAbandoned() {
        Log.w(TAG, "Consent screen closed without a result; clearing authorizing flag")
        // pendingConsentAction is kept so a late result (e.g. after activity recreation) still
        // resumes the right action; the next authorize() overwrites it.
        local.update { it.copy(authorizing = false) }
    }

    fun uploadNow() {
        if (!uiState.value.canUploadNow) return
        viewModelScope.launch { manualUpload() }
    }

    fun cancelUpload() {
        uploadScheduler.cancelRunningUploads()
    }

    /**
     * Credential Manager "Sign in with Google". Runs in [viewModelScope] so it survives activity
     * recreation while the picker is up. [activity] is required by Credential Manager. Ignored while
     * a sign-in is already in flight.
     */
    fun signIn(activity: Activity) {
        if (local.value.signingIn) return
        local.update { it.copy(signingIn = true) }
        viewModelScope.launch {
            val error: UiText? = try {
                when (val result = googleSignIn.signIn(activity)) {
                    is GoogleSignInResult.Success -> {
                        onSignedIn(result.email)
                        null
                    }
                    is GoogleSignInResult.UnexpectedCredential ->
                        UiText.Res(R.string.sign_in_failed_detail, listOf(result.type))
                    GoogleSignInResult.InvalidToken -> UiText.Res(R.string.sign_in_failed)
                    GoogleSignInResult.NoEmail -> UiText.Res(R.string.sign_in_no_email)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialException) {
                signInErrorText(e)
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed: ${e.javaClass.simpleName}")
                UiText.Res(R.string.sign_in_failed_detail, listOf(e.javaClass.simpleName))
            } finally {
                local.update { it.copy(signingIn = false) }
            }
            error?.let { _events.send(HomeEvent.ShowMessage(it)) }
        }
    }

    /** Called after Credential Manager sign-in succeeds. */
    fun onSignedIn(email: String) {
        viewModelScope.launch {
            settingsRepository.setGoogleAccountEmail(email)
            // A fresh sign-in starts a new authorization; clear a flag left stuck by a lost consent result.
            if (local.value.authorizing) {
                Log.w(TAG, "Clearing stale authorizing flag after sign-in")
                pendingConsentAction = null
                consentLaunched = false
                local.update { it.copy(authorizing = false) }
            }
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
        if (local.value.authorizing) {
            Log.w(TAG, "Authorization already in progress; ignoring request for $action")
            return
        }
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

    /** Result of the consent screen; resumes the action that needed consent. */
    fun onConsentResult(data: Intent?) {
        val resumeAction = pendingConsentAction ?: ConsentAction.RESOLVE_FOLDER
        pendingConsentAction = null
        consentLaunched = false
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
        consentLaunched = false
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

    /** Logs [e] (type and message only; no account data) and returns the message to show, or null. */
    private fun signInErrorText(e: GetCredentialException): UiText? {
        val message = e.message?.trim()?.trimEnd('.')
        Log.w(TAG, "Sign-in failed: type=${e.type} message=$message")
        return when (e) {
            is GetCredentialCancellationException ->
                if (isUserDismissal(message)) null
                else UiText.Res(R.string.sign_in_failed_config, listOf(message.orEmpty()))
            is NoCredentialException -> UiText.Res(R.string.sign_in_no_accounts)
            else -> UiText.Res(R.string.sign_in_failed_detail, listOf(message ?: e.type))
        }
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

    companion object {
        private const val TAG = "HomeViewModel"
        /**
         * True when a [GetCredentialCancellationException] message looks like the user closing the
         * picker (no message, or one that says it was cancelled). Play services also reports
         * configuration problems (unregistered signing key, consent screen in Testing) as
         * cancellation after an account is picked, e.g. "[16] Account reauth failed"; those and any
         * other unrecognised message are not treated as dismissals.
         */
        internal fun isUserDismissal(message: String?): Boolean {
            if (message.isNullOrBlank()) return true
            if (message.contains("reauth", ignoreCase = true)) return false
            return message.contains("cancel", ignoreCase = true)
        }
    }
}

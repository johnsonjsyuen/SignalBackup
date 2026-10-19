package com.johnsonyuen.signalbackup.ui.consent

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.NotSignedInException
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.domain.usecase.DriveFolderResolution
import com.johnsonyuen.signalbackup.domain.usecase.ResolveDriveFolderUseCase
import com.johnsonyuen.signalbackup.ui.common.UiText
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** The user action that needed Drive consent; resumed once access is granted. */
enum class ConsentAction { UPLOAD, CLEAN_UP, FOLDER_PICKER, RESOLVE_FOLDER }

/** Outcome of asking for Drive authorization. */
sealed interface AuthorizationStart {
    /** Already authorized; post-authorization steps have run. */
    data class Authorized(val message: UiText?) : AuthorizationStart
    /** The UI must launch this consent screen. */
    data class NeedsUi(val pendingIntent: PendingIntent) : AuthorizationStart
    data class Failed(val message: UiText) : AuthorizationStart
}

/**
 * Shared Drive authorization steps for ViewModels: request authorization, handle the consent result,
 * then resolve the Drive folder and re-apply the schedule.
 */
class DriveAuthorizationFlow @Inject constructor(
    private val driveAuthorizer: DriveAuthorizer,
    private val resolveDriveFolder: ResolveDriveFolderUseCase,
    private val uploadScheduler: UploadScheduler,
) {
    suspend fun start(): AuthorizationStart {
        val pendingIntent = try {
            driveAuthorizer.requestAuthorization()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "requestAuthorization failed: ${e.javaClass.simpleName}")
            return AuthorizationStart.Failed(errorText(e))
        }
        return if (pendingIntent == null) {
            AuthorizationStart.Authorized(afterAuthorized())
        } else {
            AuthorizationStart.NeedsUi(pendingIntent)
        }
    }

    /** Handles the consent screen result; on success runs the post-authorization steps. */
    suspend fun complete(data: Intent?): ConsentResult {
        val granted = try {
            driveAuthorizer.handleAuthorizationResult(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "handleAuthorizationResult failed: ${e.javaClass.simpleName}")
            false
        }
        if (!granted) return ConsentResult(granted = false, message = UiText.Res(R.string.consent_denied))
        return ConsentResult(granted = true, message = afterAuthorized())
    }

    /** Resolves the Drive folder and re-applies the schedule. Returns a message worth showing, if any. */
    private suspend fun afterAuthorized(): UiText? {
        val message = try {
            when (val resolution = resolveDriveFolder()) {
                is DriveFolderResolution.Ok -> null
                is DriveFolderResolution.Relocated ->
                    UiText.Res(R.string.drive_folder_relocated, listOf(resolution.folderName))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Resolving Drive folder failed: ${e.javaClass.simpleName}")
            errorText(e)
        }
        uploadScheduler.applySchedule()
        return message
    }

    data class ConsentResult(val granted: Boolean, val message: UiText?)

    companion object {
        private const val TAG = "DriveAuthFlow"

        /** Maps Drive/auth exceptions to plain-language text. */
        fun errorText(e: Throwable): UiText = when (e) {
            is NeedsConsentException -> UiText.Res(R.string.error_needs_consent)
            is NotSignedInException -> UiText.Res(R.string.error_not_signed_in)
            is DriveHttpException -> when {
                e.isQuotaExceeded -> UiText.Res(R.string.error_drive_quota)
                e.statusCode == 404 -> UiText.Res(R.string.error_drive_folder_inaccessible)
                else -> UiText.Res(R.string.error_drive_server)
            }
            is java.io.IOException -> UiText.Res(R.string.error_network)
            else -> UiText.Res(R.string.error_unknown)
        }
    }
}

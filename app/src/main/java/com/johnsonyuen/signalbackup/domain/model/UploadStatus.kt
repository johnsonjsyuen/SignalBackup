/**
 * UploadStatus.kt - Sealed interface representing the current state of an upload operation.
 *
 * This is a **state machine** for the upload process, modeled as a sealed interface. Using
 * a sealed interface (rather than an enum) allows each state to carry different data while
 * still enabling exhaustive `when` expressions in the UI.
 *
 * The states form the following flow:
 *   Idle -> Uploading -> Success | Failed | NeedsConsent
 *   NeedsConsent -> (user grants consent) -> Uploading -> Success | Failed
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/model package).
 * - Produced by [PerformUploadUseCase] and consumed by [HomeViewModel] / [UploadWorker].
 * - Drives the UI rendering in [StatusCard] -- each state maps to a different visual appearance.
 *
 * Why a sealed interface instead of a sealed class?
 * - Sealed interfaces allow `data object` subclasses (no need for `equals`/`hashCode` on
 *   stateless types like Idle and Uploading).
 * - More flexible for future extension (a class can implement multiple interfaces).
 *
 * @see domain.usecase.PerformUploadUseCase for where these states are produced
 * @see ui.component.StatusCard for where these states are rendered
 * @see ui.screen.home.HomeViewModel for where the current state is held
 */
package com.johnsonyuen.signalbackup.domain.model

import android.content.Intent

/**
 * Represents the current state of a backup upload operation.
 */
sealed interface UploadStatus {

    /** No upload is in progress or has been attempted. The initial/resting state. */
    data object Idle : UploadStatus

    /** An upload is currently in progress. The UI should show a progress indicator. */
    data object Uploading : UploadStatus

    /**
     * The upload completed successfully.
     * @property fileName The name of the uploaded .backup file.
     * @property fileSizeBytes The size of the uploaded file in bytes.
     */
    data class Success(val fileName: String, val fileSizeBytes: Long) : UploadStatus

    /**
     * The upload failed.
     * @property error A human-readable error message describing what went wrong.
     */
    data class Failed(val error: String) : UploadStatus

    /**
     * The Google Drive API requires the user to grant OAuth consent before the upload
     * can proceed. The UI should launch the [consentIntent] to show Google's consent dialog.
     *
     * This state occurs when GoogleAccountCredential's OAuth token is expired or the
     * DRIVE scope hasn't been granted yet. After the user grants consent, the
     * upload should be retried.
     *
     * @property consentIntent An Android Intent that launches Google's OAuth consent screen.
     */
    data class NeedsConsent(val consentIntent: Intent) : UploadStatus
}

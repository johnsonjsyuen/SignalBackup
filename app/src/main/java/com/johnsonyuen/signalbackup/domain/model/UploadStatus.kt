package com.johnsonyuen.signalbackup.domain.model

import android.content.Intent

sealed interface UploadStatus {
    data object Idle : UploadStatus
    data object Uploading : UploadStatus
    data class Success(val fileName: String, val fileSizeBytes: Long) : UploadStatus
    data class Failed(val error: String) : UploadStatus
    data class NeedsConsent(val consentIntent: Intent) : UploadStatus
}

package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import javax.inject.Inject

/** Starts a user-initiated upload now (no-op if one is already queued or running). */
class ManualUploadUseCase @Inject constructor(
    private val uploadScheduler: UploadScheduler,
) {
    suspend operator fun invoke() {
        uploadScheduler.enqueueManualUpload()
    }
}

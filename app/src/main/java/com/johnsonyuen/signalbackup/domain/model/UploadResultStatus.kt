package com.johnsonyuen.signalbackup.domain.model

enum class UploadResultStatus {
    /** Mirror complete and verified. */
    SUCCESS,
    /** Run failed; see failureReason. */
    FAILED,
    /** Run completed but Signal hasn't written a new backup recently. */
    STALE,
}

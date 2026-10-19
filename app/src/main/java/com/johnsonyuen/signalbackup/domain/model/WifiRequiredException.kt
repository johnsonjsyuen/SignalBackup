package com.johnsonyuen.signalbackup.domain.model

/**
 * The user requires Wi-Fi for uploads but the active network is metered or absent.
 * Transient: the run should be retried later; the resumable session is kept.
 */
class WifiRequiredException(
    message: String = "Upload paused: Wi-Fi required but device is on a metered network",
) : Exception(message)

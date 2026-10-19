package com.johnsonyuen.signalbackup.domain.model

/** Progress for a whole upload run (may span several files). */
fun interface MirrorProgressListener {
    /**
     * @param bytesUploaded Bytes of this run's pending total confirmed by Drive so far.
     * @param totalBytes Total bytes this run needs to send.
     * @param currentFileName File currently being sent, or null.
     */
    fun onProgress(bytesUploaded: Long, totalBytes: Long, currentFileName: String?)
}

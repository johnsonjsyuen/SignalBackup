package com.johnsonyuen.signalbackup.domain.upload

import android.util.Log

/** Short engine log lines; never pass URIs, tokens or file contents. Safe in plain JVM tests. */
internal object UploadLog {
    private const val TAG = "UploadEngine"

    fun d(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    fun w(message: String) {
        runCatching { Log.w(TAG, message) }
    }
}

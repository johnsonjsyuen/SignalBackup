package com.johnsonyuen.signalbackup.ui.navigation

import com.johnsonyuen.signalbackup.notification.NotificationIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A fix action requested by a notification tap. */
enum class FixAction { CONSENT, SETTINGS, HISTORY;
    companion object {
        fun fromExtra(value: String?): FixAction? = when (value) {
            NotificationIntents.ACTION_CONSENT -> CONSENT
            NotificationIntents.ACTION_SETTINGS -> SETTINGS
            NotificationIntents.ACTION_HISTORY -> HISTORY
            else -> null
        }
    }
}

/** Hands notification fix actions from MainActivity to the navigation graph and screens. */
@Singleton
class FixActionBus @Inject constructor() {
    private val _pending = MutableStateFlow<FixAction?>(null)
    val pending: StateFlow<FixAction?> = _pending.asStateFlow()

    fun post(action: FixAction) {
        _pending.value = action
    }

    /** Clears [action] if it is still pending; returns true when this caller consumed it. */
    fun consume(action: FixAction): Boolean {
        var consumed = false
        _pending.update { current ->
            if (current == action) {
                consumed = true
                null
            } else {
                consumed = false
                current
            }
        }
        return consumed
    }
}

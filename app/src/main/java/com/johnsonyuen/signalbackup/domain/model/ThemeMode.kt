package com.johnsonyuen.signalbackup.domain.model

/**
 * Represents the user's preferred theme setting.
 *
 * - [SYSTEM]: Follow the device's system-wide dark mode setting (default).
 * - [LIGHT]: Always use the light color scheme.
 * - [DARK]: Always use the dark color scheme.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** Human-readable label for display in the settings UI. */
    val displayName: String
        get() = when (this) {
            SYSTEM -> "System default"
            LIGHT -> "Light"
            DARK -> "Dark"
        }

    companion object {
        /** Safely parse a stored string back to a [ThemeMode], defaulting to [SYSTEM]. */
        fun fromString(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

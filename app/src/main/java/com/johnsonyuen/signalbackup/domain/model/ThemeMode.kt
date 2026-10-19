package com.johnsonyuen.signalbackup.domain.model

/** The user's preferred theme. Labels live in the UI layer (string resources). */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /** Parses a stored value, defaulting to [SYSTEM]. */
        fun fromString(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

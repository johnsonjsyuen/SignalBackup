/**
 * DriveFolder.kt - Domain model representing a Google Drive folder.
 *
 * A simple data class that holds the ID and display name of a Google Drive folder.
 * Used throughout the app when working with Drive folder selection and navigation.
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/model package).
 * - Created by [GoogleDriveService] when listing or creating folders.
 * - Used by [DriveRepository], [SettingsViewModel] (folder navigation stack),
 *   and [DriveFolderPickerDialog] (folder list UI).
 *
 * @see data.remote.GoogleDriveService for where Drive folders are fetched
 * @see ui.component.DriveFolderPickerDialog for the folder selection UI
 */
package com.johnsonyuen.signalbackup.domain.model

/**
 * Represents a folder in Google Drive with its ID and display name.
 *
 * @property id The Google Drive folder ID (used in API calls).
 * @property name The human-readable folder name (displayed in the UI).
 */
data class DriveFolder(val id: String, val name: String)

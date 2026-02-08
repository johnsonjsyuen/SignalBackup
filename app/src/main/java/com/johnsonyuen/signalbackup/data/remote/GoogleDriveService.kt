/**
 * GoogleDriveService.kt - Google Drive API wrapper for file and folder operations.
 *
 * This class encapsulates all direct interactions with the Google Drive REST API v3.
 * It provides three core operations: uploading files, listing folders, and creating folders.
 *
 * Architecture context:
 * - Part of the **data layer** (data/remote package) -- this is the "remote data source."
 * - Used exclusively by [DriveRepositoryImpl], which wraps it behind the [DriveRepository]
 *   interface for clean architecture separation.
 * - Depends on [GoogleAccountCredential] (provided by Hilt via DriveModule) for OAuth2
 *   authentication, and [SettingsDataStore] to read the signed-in account email.
 *
 * Authentication pattern:
 * - Before every API call, [ensureAccount] reads the stored email from DataStore and sets
 *   it on the [GoogleAccountCredential]. This is necessary because GoogleAccountCredential
 *   is a singleton that may not yet know which account to authenticate as.
 * - If no email is stored, it throws IllegalStateException -- callers must ensure the user
 *   has signed in before attempting Drive operations.
 * - If the user's OAuth token has expired or the required scope hasn't been granted,
 *   the Drive API throws UserRecoverableAuthIOException, which is caught upstream in
 *   [PerformUploadUseCase] and surfaces as [UploadStatus.NeedsConsent].
 *
 * Threading: All API methods use `withContext(Dispatchers.IO)` because Google's Drive
 * client library makes synchronous HTTP calls that would block the main thread.
 *
 * @see data.repository.DriveRepository for the clean interface consumed by use cases
 * @see di.DriveModule for how GoogleAccountCredential is provided
 * @see domain.usecase.PerformUploadUseCase for the upload flow that calls this service
 */
package com.johnsonyuen.signalbackup.data.remote

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

/**
 * Service class that wraps the Google Drive API v3 client.
 *
 * Injected by Hilt with a [GoogleAccountCredential] for OAuth2 and [SettingsDataStore]
 * for reading the signed-in account. All methods are suspend functions that run on
 * [Dispatchers.IO] to avoid blocking the main thread.
 *
 * @param credential Google OAuth2 credential object. Its `selectedAccountName` is set
 *        dynamically before each API call via [ensureAccount].
 * @param settingsDataStore Used to read the stored Google account email.
 */
class GoogleDriveService @Inject constructor(
    private val credential: GoogleAccountCredential,
    private val settingsDataStore: SettingsDataStore,
) {

    /**
     * Lazily initialized Google Drive API client.
     *
     * We use `by lazy` to defer construction until the first API call, because the
     * Drive client requires the credential to be configured. The Drive.Builder pattern
     * is Google's standard way to construct API clients:
     * - [NetHttpTransport]: HTTP layer for making network requests.
     * - [GsonFactory]: JSON parser for serializing/deserializing API responses.
     * - [credential]: OAuth2 credential that attaches the access token to each request.
     */
    private val drive: Drive by lazy {
        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential,
        )
            .setApplicationName("SignalBackup")
            .build()
    }

    /**
     * Ensures the credential has a valid account name set before making API calls.
     *
     * GoogleAccountCredential requires `selectedAccountName` to be set so it knows
     * which Google account's OAuth token to use. We read the email from DataStore
     * (where it was saved during sign-in) and set it on the credential.
     *
     * This must be called before every API operation because the credential is a
     * shared singleton -- if the user signs out and signs in with a different account,
     * we need to pick up the new email.
     *
     * @throws IllegalStateException if no Google account email is stored (user not signed in).
     */
    private suspend fun ensureAccount() {
        val email = settingsDataStore.googleAccountEmail.first()
            ?: throw IllegalStateException("No Google account configured. Please sign in first.")
        credential.selectedAccountName = email
    }

    /**
     * Uploads a file to Google Drive.
     *
     * Creates a new file in the specified Drive folder with the given content.
     * The file is uploaded as a single request (not resumable), which works well
     * for typical Signal backup files.
     *
     * @param folderId The Google Drive folder ID to upload into.
     * @param fileName The name for the file in Drive.
     * @param mimeType The MIME type of the file content (typically "application/octet-stream").
     * @param inputStream The file content to upload. This stream is consumed and should
     *        not be reused after this call.
     * @return The Google Drive file ID of the newly uploaded file.
     * @throws com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
     *         if the OAuth token is expired or the Drive scope hasn't been granted.
     */
    suspend fun uploadFile(
        folderId: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
    ): String = withContext(Dispatchers.IO) {
        ensureAccount()

        // Create Drive file metadata specifying the name and parent folder.
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = fileName
            parents = listOf(folderId)
        }

        // Wrap the InputStream in Drive's InputStreamContent for the upload.
        val mediaContent = InputStreamContent(mimeType, inputStream)

        // Execute the upload. setFields("id") tells Drive to only return the file ID
        // in the response, reducing bandwidth and parsing overhead.
        val file = drive.files().create(fileMetadata, mediaContent)
            .setFields("id")
            .execute()
        file.id
    }

    /**
     * Lists all subfolders within a given Drive folder.
     *
     * Used by the Drive Folder Picker dialog to let users navigate the Drive folder
     * hierarchy and select a destination for uploads.
     *
     * @param parentId The folder ID to list children of, or null for the root ("My Drive").
     * @return A list of [DriveFolder] objects (id + name), sorted alphabetically by name.
     */
    suspend fun listFolders(parentId: String?): List<DriveFolder> = withContext(Dispatchers.IO) {
        ensureAccount()

        // Build the search query to find only folders (not files) that are not in trash.
        // If parentId is provided, filter to that parent; otherwise show root-level folders.
        val query = buildString {
            append("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            if (parentId != null) {
                append(" and '$parentId' in parents")
            } else {
                append(" and 'root' in parents")
            }
        }

        // Execute the query. setSpaces("drive") limits search to the user's Drive
        // (as opposed to appDataFolder or photos). setFields limits the response to
        // only the fields we need, reducing data transfer.
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .setOrderBy("name")
            .execute()

        // Map Google's File objects to our simpler DriveFolder data class.
        result.files?.map { DriveFolder(it.id, it.name) } ?: emptyList()
    }

    /**
     * Creates a new folder in Google Drive.
     *
     * Used by the Drive Folder Picker dialog's "New Folder" button to let users
     * create destination folders without leaving the app.
     *
     * @param name The display name for the new folder.
     * @param parentId The parent folder ID, or null to create at the root level.
     * @return A [DriveFolder] with the ID and name of the newly created folder.
     */
    suspend fun createFolder(name: String, parentId: String?): DriveFolder =
        withContext(Dispatchers.IO) {
            ensureAccount()

            // In Google Drive, folders are just files with a special MIME type.
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                this.name = name
                this.mimeType = "application/vnd.google-apps.folder"
                if (parentId != null) {
                    parents = listOf(parentId)
                }
            }

            val file = drive.files().create(fileMetadata)
                .setFields("id, name")
                .execute()
            DriveFolder(file.id, file.name)
        }
}

package com.johnsonyuen.signalbackup.data.local.entity

import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.NewUploadRecord
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import java.time.Instant

fun SettingsEntity.toAppSettings(): AppSettings = AppSettings(
    localFolderUri = localFolderUri,
    driveFolderId = driveFolderId,
    driveFolderName = driveFolderName,
    scheduleEnabled = scheduleEnabled,
    scheduleHour = scheduleHour,
    scheduleMinute = scheduleMinute,
    googleAccountEmail = googleAccountEmail,
    themeMode = themeMode,
    wifiOnly = wifiOnly,
)

/** Unknown stored status values (forward compatibility) read as FAILED. */
fun UploadHistoryEntity.toUploadRecord(): UploadRecord = UploadRecord(
    id = id,
    timestamp = Instant.ofEpochMilli(timestamp),
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    bytesUploaded = bytesUploaded,
    status = UploadResultStatus.entries.firstOrNull { it.name == status } ?: UploadResultStatus.FAILED,
    failureReason = failureReason?.let { name -> FailureReason.entries.firstOrNull { it.name == name } },
    errorMessage = errorMessage,
    driveFolderId = driveFolderId,
    driveFileId = driveFileId,
    md5Checksum = md5Checksum,
)

fun NewUploadRecord.toEntity(): UploadHistoryEntity = UploadHistoryEntity(
    timestamp = timestamp.toEpochMilli(),
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    bytesUploaded = bytesUploaded,
    status = status.name,
    failureReason = failureReason?.name,
    errorMessage = errorMessage,
    driveFolderId = driveFolderId,
    driveFileId = driveFileId,
    md5Checksum = md5Checksum,
)

fun UploadSessionEntity.toDomain(): ResumableUploadSession = ResumableUploadSession(
    sessionUri = sessionUri,
    localFileUri = localFileUri,
    relativePath = relativePath,
    fileName = fileName,
    totalBytes = totalBytes,
    localLastModifiedMillis = localLastModifiedMillis,
    bytesUploaded = bytesUploaded,
    driveParentId = driveParentId,
    createdAtMillis = createdAtMillis,
    driveFileId = driveFileId,
    remoteMd5 = remoteMd5,
    failureCount = failureCount,
)

fun ResumableUploadSession.toEntity(): UploadSessionEntity = UploadSessionEntity(
    sessionUri = sessionUri,
    localFileUri = localFileUri,
    relativePath = relativePath,
    fileName = fileName,
    totalBytes = totalBytes,
    localLastModifiedMillis = localLastModifiedMillis,
    bytesUploaded = bytesUploaded,
    driveParentId = driveParentId,
    createdAtMillis = createdAtMillis,
    driveFileId = driveFileId,
    remoteMd5 = remoteMd5,
    failureCount = failureCount,
)

fun SyncedFileEntity.toDomain(): SyncedFile = SyncedFile(
    relativePath = relativePath,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    lastModifiedMillis = lastModifiedMillis,
    driveFileId = driveFileId,
    md5Checksum = md5Checksum,
    syncedAtMillis = syncedAtMillis,
)

fun SyncedFile.toEntity(): SyncedFileEntity = SyncedFileEntity(
    relativePath = relativePath,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    lastModifiedMillis = lastModifiedMillis,
    driveFileId = driveFileId,
    md5Checksum = md5Checksum,
    syncedAtMillis = syncedAtMillis,
)

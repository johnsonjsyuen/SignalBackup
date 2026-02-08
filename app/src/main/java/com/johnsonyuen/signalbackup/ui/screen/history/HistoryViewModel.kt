package com.johnsonyuen.signalbackup.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    uploadHistoryRepository: UploadHistoryRepository
) : ViewModel() {

    val records: StateFlow<List<UploadRecord>> = uploadHistoryRepository.getAll()
        .map { entities ->
            entities.map { entity ->
                UploadRecord(
                    id = entity.id,
                    timestamp = Instant.ofEpochMilli(entity.timestamp),
                    fileName = entity.fileName,
                    fileSizeBytes = entity.fileSizeBytes,
                    status = try {
                        UploadResultStatus.valueOf(entity.status)
                    } catch (e: Exception) {
                        UploadResultStatus.FAILED
                    },
                    errorMessage = entity.errorMessage,
                    driveFolderId = entity.driveFolderId,
                    driveFileId = entity.driveFileId
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

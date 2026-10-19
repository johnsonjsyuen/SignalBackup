package com.johnsonyuen.signalbackup.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HistoryUiState(val loading: Boolean = true, val records: List<UploadRecord> = emptyList())

/** Upload history, newest first. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    historyRepository: UploadHistoryRepository,
) : ViewModel() {
    val uiState: StateFlow<HistoryUiState> = historyRepository.getAll()
        .map { HistoryUiState(loading = false, records = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())
}

package com.johnsonyuen.signalbackup.ui.screen.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val performUploadUseCase: PerformUploadUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uploadStatus = MutableStateFlow<UploadStatus>(UploadStatus.Idle)
    val uploadStatus: StateFlow<UploadStatus> = _uploadStatus.asStateFlow()

    val googleAccountEmail: StateFlow<String?> = settingsRepository.googleAccountEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val scheduleHour: StateFlow<Int> = settingsRepository.scheduleHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val scheduleMinute: StateFlow<Int> = settingsRepository.scheduleMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val localFolderUri: StateFlow<String?> = settingsRepository.localFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val driveFolderName: StateFlow<String?> = settingsRepository.driveFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun uploadNow(context: Context) {
        viewModelScope.launch {
            _uploadStatus.value = UploadStatus.Uploading
            _uploadStatus.value = performUploadUseCase(context)
        }
    }

    fun setGoogleAccountEmail(email: String) {
        viewModelScope.launch {
            settingsRepository.setGoogleAccountEmail(email)
        }
    }
}

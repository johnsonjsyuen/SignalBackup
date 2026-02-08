/**
 * HistoryViewModel.kt - ViewModel for the History screen, providing upload records.
 *
 * This is one of the simplest ViewModels in the app. It has a single responsibility:
 * expose the list of past upload records as a reactive StateFlow for the UI to observe.
 *
 * How it works:
 * 1. [UploadHistoryRepository.getAll()] returns a `Flow<List<UploadRecord>>` backed by
 *    Room's `@Query` with a `Flow` return type. Room automatically re-emits the list
 *    whenever the upload_history table changes (insert, update, delete).
 * 2. `.stateIn()` converts the cold Flow to a hot StateFlow, giving the UI an immediate
 *    value (emptyList()) while the first database query completes.
 * 3. `SharingStarted.WhileSubscribed(5000)` keeps the Flow active for 5 seconds after
 *    the last UI collector disconnects. This prevents unnecessary database re-queries
 *    during quick configuration changes (e.g., screen rotation).
 *
 * Why so simple?
 * The repository already returns domain-model [UploadRecord] objects (mapped from
 * entities in [UploadHistoryRepositoryImpl] using the [toUploadRecord] extension).
 * No additional transformation is needed in the ViewModel.
 *
 * @see data.repository.UploadHistoryRepository for the data source
 * @see domain.model.UploadRecord for the domain model
 * @see ui.screen.history.HistoryScreen for the UI that observes this ViewModel
 */
package com.johnsonyuen.signalbackup.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the History screen.
 *
 * @param uploadHistoryRepository Provides a reactive Flow of all upload history records.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    uploadHistoryRepository: UploadHistoryRepository
) : ViewModel() {

    /**
     * All upload history records, ordered by timestamp (newest first).
     *
     * Backed by Room's reactive query -- automatically updates when new uploads
     * are recorded by [PerformUploadUseCase].
     */
    val records: StateFlow<List<UploadRecord>> = uploadHistoryRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

/**
 * HistoryScreen.kt - The History screen that lists past upload records.
 *
 * This screen displays all previous backup upload attempts (both successful and failed)
 * in a scrollable list. Each record is rendered as a [HistoryItem] card showing the
 * file name, timestamp, status, file size, and any error message.
 *
 * Two states:
 * 1. **Empty state**: When no uploads have been performed yet, shows a centered
 *    "No upload history yet" message.
 * 2. **List state**: When records exist, displays them in a LazyColumn with padding
 *    and spacing between items.
 *
 * Key Compose concepts:
 * - **LazyColumn**: A vertically scrolling list that only composes and lays out the
 *   items that are currently visible on screen. Efficient for potentially long lists.
 * - **items(records, key = { it.id })**: Provides a stable key for each item based on
 *   the record's database ID. This helps Compose correctly identify and animate items
 *   when the list changes (e.g., a new upload is added at the top).
 * - **PaddingValues**: Adds padding around the entire list content (not per-item).
 * - **Arrangement.spacedBy(8.dp)**: Adds consistent vertical spacing between items.
 * - **collectAsStateWithLifecycle()**: Lifecycle-aware collection of the records StateFlow.
 *
 * @see ui.screen.history.HistoryViewModel for the data source
 * @see ui.component.HistoryItem for the individual record card
 */
package com.johnsonyuen.signalbackup.ui.screen.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnsonyuen.signalbackup.ui.component.HistoryItem

/**
 * The History screen composable.
 *
 * @param viewModel The Hilt-injected HistoryViewModel providing the upload records.
 */
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    // Collect the list of upload records as lifecycle-aware Compose State.
    val records by viewModel.records.collectAsStateWithLifecycle()

    if (records.isEmpty()) {
        // Empty state -- centered message when no uploads have been performed.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No upload history yet", style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        // List state -- LazyColumn with HistoryItem cards.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),      // Padding around the entire list.
            verticalArrangement = Arrangement.spacedBy(8.dp)  // Spacing between items.
        ) {
            // Render each record as a HistoryItem card.
            // key = { it.id } provides stable identity for animations and recycling.
            items(records, key = { it.id }) { record ->
                HistoryItem(record = record)
            }
        }
    }
}

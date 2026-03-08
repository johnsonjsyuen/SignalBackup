/**
 * DataStoreExt.kt - Shared DataStore instance via a Context extension property.
 *
 * The `preferencesDataStore` delegate creates exactly one DataStore instance per process
 * for the given file name. This top-level extension property ensures that both Hilt-injected
 * components (via AppModule) and non-DI components (e.g., BroadcastReceivers like
 * UploadAlarmReceiver) access the same singleton DataStore instance.
 *
 * DataStore MUST have exactly one instance per file name -- creating multiple instances
 * for the same file causes corruption.
 *
 * @see di.AppModule.provideDataStore for Hilt integration
 * @see receiver.UploadAlarmReceiver for non-DI usage
 */
package com.johnsonyuen.signalbackup.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * Singleton Preferences DataStore instance, backed by a file named "settings".
 *
 * Usage: `context.dataStore` from any component that has a Context reference.
 */
val Context.dataStore by preferencesDataStore(name = "settings")

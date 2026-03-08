/**
 * MainActivity.kt - The single Activity that hosts the entire Compose UI.
 *
 * This app follows the "single Activity" architecture pattern, which is the recommended
 * approach for modern Android apps using Jetpack Compose. Instead of having multiple
 * Activities for different screens, we have one Activity that hosts a Compose NavHost,
 * and navigation between screens is handled entirely within Compose.
 *
 * Key Android concepts used here:
 * - **ComponentActivity**: The base class for activities that use Jetpack Compose (rather
 *   than the older AppCompatActivity which was for XML-based layouts).
 * - **@AndroidEntryPoint**: Hilt annotation that enables dependency injection in this
 *   Activity and any Composables it hosts (via hiltViewModel()).
 * - **enableEdgeToEdge()**: Makes the app draw behind system bars (status bar, navigation
 *   bar) for a modern, immersive UI appearance.
 * - **setContent {}**: The Compose equivalent of setContentView() -- it sets the root
 *   Composable function that defines the entire UI tree.
 *
 * Data flow: MainActivity -> SignalBackupTheme (with ThemeMode) -> AppNavGraph -> individual screens
 *
 * @see ui.navigation.AppNavGraph for the navigation structure
 * @see ui.theme.Theme for the Material3 theming setup
 * @see domain.model.ThemeMode for theme mode options (System/Light/Dark)
 */
package com.johnsonyuen.signalbackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.ui.navigation.AppNavGraph
import com.johnsonyuen.signalbackup.ui.theme.SignalBackupTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The main (and only) Activity in the app.
 *
 * [@AndroidEntryPoint] tells Hilt to generate the necessary code for dependency injection.
 * This is required for any Activity that needs to use `hiltViewModel()` in its Compose content,
 * because Hilt needs to set up the DI scope at the Activity level.
 *
 * The Activity injects [SettingsRepository] directly (via field injection) to read the user's
 * theme preference at the very top level of the Compose tree, before any themed content renders.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected SettingsRepository for reading the user's theme mode preference.
     *
     * We use field injection here ([@Inject] on a lateinit var) because Android
     * instantiates Activities itself -- we cannot use constructor injection.
     * Hilt populates this field after the Activity is created but before onCreate runs.
     */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /**
     * Called when the Activity is first created (or recreated after a configuration change).
     *
     * @param savedInstanceState Bundle containing saved state from a previous instance,
     *        or null if this is a fresh creation. We do not use saved instance state here
     *        because Compose and ViewModels handle state preservation for us.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display, allowing the app to draw behind system bars.
        // This gives a more modern appearance where content flows under the status bar
        // and navigation bar, with appropriate padding handled by Scaffold.
        enableEdgeToEdge()

        // Set the Compose UI content. This replaces the traditional setContentView(R.layout.xxx)
        // used with XML layouts. Everything inside setContent {} is declarative Compose UI.
        setContent {
            // Observe the theme mode preference from DataStore as Compose state.
            // collectAsStateWithLifecycle() is lifecycle-aware: it automatically starts/stops
            // collection when the Activity enters/leaves the foreground, preventing wasted work.
            // The initial value "SYSTEM" is used until the DataStore emits its first value.
            val themeModeString by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = "SYSTEM")

            // Convert the stored string ("SYSTEM", "LIGHT", "DARK") to a type-safe enum.
            // ThemeMode.fromString safely defaults to SYSTEM for unrecognized values.
            val themeMode = ThemeMode.fromString(themeModeString)

            // Apply the app's Material3 theme (colors, typography, shapes).
            // SignalBackupTheme supports dynamic colors on Android 12+ and
            // uses the themeMode to determine light/dark/system appearance.
            SignalBackupTheme(themeMode = themeMode) {
                // AppNavGraph is the root composable that sets up the Scaffold
                // (top bar + bottom navigation bar) and the NavHost containing
                // all three screens: Home, History, and Settings.
                AppNavGraph()
            }
        }
    }
}

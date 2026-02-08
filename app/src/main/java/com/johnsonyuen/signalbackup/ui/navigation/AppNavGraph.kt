/**
 * AppNavGraph.kt - Root navigation graph and shell UI for the entire app.
 *
 * This file defines:
 * 1. **Type-safe route objects** (HomeRoute, HistoryRoute, SettingsRoute) using
 *    kotlinx.serialization. Each route is a `@Serializable data object`, which lets
 *    Navigation Compose generate type-safe route strings at compile time instead of
 *    using raw string paths like "home" or "settings/{id}".
 * 2. **BottomNavItem** -- a simple data class tying a label, icon, and route together
 *    for the bottom navigation bar.
 * 3. **AppNavGraph composable** -- the top-level composable that sets up the Material3
 *    Scaffold (top bar + bottom nav bar) and the NavHost (content area).
 *
 * Navigation architecture:
 * - Uses a single NavController shared across all three tabs.
 * - Bottom bar items are highlighted based on the current destination's hierarchy
 *   (supports nested navigation graphs if added later).
 * - Tab switching uses `popUpTo(startDestination) + saveState + restoreState` to
 *   preserve each tab's back-stack state when switching between tabs.
 * - The HomeScreen receives an `onNavigateToSettings` callback so it can programmatically
 *   switch to the Settings tab (e.g., via the "setup" chips).
 *
 * Key Compose/Navigation concepts:
 * - **rememberNavController()**: Creates and remembers a NavHostController across
 *   recompositions. Must be created at or above the Scaffold level.
 * - **currentBackStackEntryAsState()**: Converts the NavController's current back-stack
 *   entry into Compose State, causing recomposition when navigation occurs.
 * - **NavDestination.hierarchy**: The chain of parent destinations leading to the current
 *   one. Used to determine which bottom bar item should be "selected."
 * - **hasRoute()**: Type-safe check -- does this destination match the given route class?
 * - **launchSingleTop**: Prevents creating duplicate instances of the same destination
 *   on top of the back-stack (like Android's singleTop launch mode).
 * - **saveState / restoreState**: Preserves the back-stack and view state of the tab
 *   you are leaving, and restores it when you return to that tab.
 *
 * @see ui.screen.home.HomeScreen for the Home tab content
 * @see ui.screen.history.HistoryScreen for the History tab content
 * @see ui.screen.settings.SettingsScreen for the Settings tab content
 */
package com.johnsonyuen.signalbackup.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.johnsonyuen.signalbackup.ui.screen.home.HomeScreen
import com.johnsonyuen.signalbackup.ui.screen.history.HistoryScreen
import com.johnsonyuen.signalbackup.ui.screen.settings.SettingsScreen
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Type-safe route definitions.
// Each is a @Serializable data object so Navigation Compose can generate
// compile-time-safe route strings. Using data objects (instead of strings like
// "home") means typos are caught by the compiler.
// ---------------------------------------------------------------------------

/** Route for the Home tab -- shows upload status, countdown, and "Upload Now" button. */
@Serializable data object HomeRoute

/** Route for the History tab -- lists past upload records from Room. */
@Serializable data object HistoryRoute

/** Route for the Settings tab -- local folder, Drive folder, schedule, theme, account. */
@Serializable data object SettingsRoute

/**
 * Associates a bottom navigation tab with its label, icon, and destination route.
 *
 * @param T The type-safe route type (e.g., HomeRoute).
 * @param label Human-readable tab label shown below the icon.
 * @param icon Material icon displayed in the bottom navigation bar.
 * @param route The route object to navigate to when this tab is tapped.
 */
data class BottomNavItem<T : Any>(
    val label: String,
    val icon: ImageVector,
    val route: T
)

/**
 * The root composable for the app. Sets up:
 * - A Material3 Scaffold with a TopAppBar and NavigationBar.
 * - A NavHost that renders the correct screen composable for the current route.
 *
 * This composable is called from [MainActivity.setContent] and fills the entire screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph() {
    // Create and remember the NavController. This single instance is shared
    // by the bottom bar (for navigation) and the NavHost (for rendering).
    val navController = rememberNavController()

    // Define the three bottom navigation tabs.
    val items = listOf(
        BottomNavItem("Home", Icons.Default.Home, HomeRoute),
        BottomNavItem("History", Icons.Default.History, HistoryRoute),
        BottomNavItem("Settings", Icons.Default.Settings, SettingsRoute)
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Signal Backup") })
        },
        bottomBar = {
            NavigationBar {
                // Observe the current navigation state as Compose State.
                // This causes the bottom bar to recompose when the user navigates.
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        // Check if this tab's route matches the current destination
                        // (or any ancestor in the hierarchy, for nested graphs).
                        selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(item.route::class)
                        } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                // Pop up to the start destination to avoid building up
                                // a tall back-stack when switching tabs repeatedly.
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true   // Save the tab's back-stack state
                                }
                                launchSingleTop = true  // No duplicate destinations
                                restoreState = true     // Restore previous state on return
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        // NavHost renders the composable that matches the current route.
        // innerPadding accounts for the top bar and bottom bar so content
        // does not render underneath them.
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Home tab -- receives a callback to programmatically navigate to Settings
            // (used by the "setup needed" chips on the Home screen).
            composable<HomeRoute> { HomeScreen(onNavigateToSettings = {
                navController.navigate(SettingsRoute) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }) }

            // History tab -- displays past upload records.
            composable<HistoryRoute> { HistoryScreen() }

            // Settings tab -- configuration for folders, schedule, theme, and account.
            composable<SettingsRoute> { SettingsScreen() }
        }
    }
}

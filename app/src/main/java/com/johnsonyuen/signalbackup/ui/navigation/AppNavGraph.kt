package com.johnsonyuen.signalbackup.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.ui.screen.history.HistoryScreen
import com.johnsonyuen.signalbackup.ui.screen.home.HomeScreen
import com.johnsonyuen.signalbackup.ui.screen.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute

private data class BottomNavItem(@StringRes val label: Int, val icon: ImageVector, val route: Any)

private val navItems = listOf(
    BottomNavItem(R.string.nav_home, Icons.Default.Home, HomeRoute),
    BottomNavItem(R.string.nav_history, Icons.Default.History, HistoryRoute),
    BottomNavItem(R.string.nav_settings, Icons.Default.Settings, SettingsRoute),
)

private fun NavHostController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Bottom-navigation shell. Routes notification fix actions from [fixActionBus] to tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(fixActionBus: FixActionBus) {
    val navController = rememberNavController()
    val pendingFix by fixActionBus.pending.collectAsStateWithLifecycle()

    LaunchedEffect(pendingFix) {
        val fix = pendingFix
        if (fix == FixAction.CONSENT) {
            // HomeViewModel consumes CONSENT and starts authorization.
            navController.navigateToTab(HomeRoute)
        } else if (fix != null && fixActionBus.consume(fix)) {
            navController.navigateToTab(if (fix == FixAction.SETTINGS) SettingsRoute else HistoryRoute)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val destination = backStackEntry?.destination
                navItems.forEach { item ->
                    NavigationBarItem(
                        // The label already names the tab; avoid TalkBack reading it twice.
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                        selected = destination?.hierarchy?.any { it.hasRoute(item.route::class) } == true,
                        onClick = { navController.navigateToTab(item.route) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<HomeRoute> {
                HomeScreen(onNavigateToSettings = { navController.navigateToTab(SettingsRoute) })
            }
            composable<HistoryRoute> { HistoryScreen() }
            composable<SettingsRoute> { SettingsScreen() }
        }
    }
}

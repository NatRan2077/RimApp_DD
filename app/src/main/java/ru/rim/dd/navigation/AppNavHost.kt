package ru.rim.dd.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.rim.dd.feature.info.InfoScreen
import ru.rim.dd.feature.network.NetworkScreen
import ru.rim.dd.feature.pairing.PairingScreen
import ru.rim.dd.feature.readings.ReadingsScreen
import ru.rim.dd.feature.settings.SettingsScreen

private object Routes {
    const val PAIRING = "pairing"
    const val READINGS = "readings"
    const val NETWORK = "network"
    const val INFO = "info"
    const val SETTINGS = "settings"
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.READINGS, "Показания", Icons.Filled.List),
    BottomTab(Routes.NETWORK, "Сеть", Icons.Filled.Wifi),
    BottomTab(Routes.INFO, "Инфо", Icons.Filled.Info),
    BottomTab(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

/**
 * Навигационный граф приложения: экран подключения — без нижней панели,
 * четыре основных экрана — с ней (см. схему из ТЗ, п. 5).
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != Routes.PAIRING

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = backStackEntry?.destination?.hierarchy
                                ?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { androidx.compose.material3.Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PAIRING,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.PAIRING) {
                PairingScreen(onConnected = { navController.navigate(Routes.READINGS) })
            }
            composable(Routes.READINGS) { ReadingsScreen() }
            composable(Routes.NETWORK) { NetworkScreen() }
            composable(Routes.INFO) { InfoScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}

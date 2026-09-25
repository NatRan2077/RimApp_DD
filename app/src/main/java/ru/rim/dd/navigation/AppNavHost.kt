package ru.rim.dd.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.feature.info.InfoScreen
import ru.rim.dd.feature.network.NetworkScreen
import ru.rim.dd.feature.pairing.PairingScreen
import ru.rim.dd.feature.readings.ReadingsScreen
import ru.rim.dd.feature.settings.AppSettingsViewModel
import ru.rim.dd.feature.settings.SettingsScreen
import ru.rim.dd.ui.theme.RimPrimary
import ru.rim.dd.ui.theme.RimPrimaryDeep
import ru.rim.dd.ui.theme.RimTheme
import ru.rim.dd.ui.theme.RimViolet

private object Routes {
    const val PAIRING = "pairing"
    const val READINGS = "readings"
    const val NETWORK = "network"
    const val INFO = "info"
    const val SETTINGS = "settings"
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.READINGS, "Показания", Icons.Filled.List),
    BottomTab(Routes.NETWORK, "Сеть", Icons.Filled.Wifi),
    BottomTab(Routes.INFO, "Инфо", Icons.Filled.Info),
    BottomTab(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

/**
 * [Android-патч] Навигация приложения с оформлением из макета Figma. Тема (тёмная/светлая) и
 * показ OBIS — глобальные настройки (AppSettingsViewModel), поэтому RimTheme оборачивает ВСЁ
 * дерево здесь, а showObis/тумблеры пробрасываются в экраны. Экран подключения — без нижней
 * панели, четыре основных — с кастомной панелью (см. RimBottomBar).
 */
@Composable
fun AppNavHost() {
    val appSettings: AppSettingsViewModel = hiltViewModel()
    val dark by appSettings.darkTheme.collectAsState()
    val showObis by appSettings.showObis.collectAsState()

    RimTheme(dark = dark) {
        val palette = RimTheme.palette
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = currentRoute != Routes.PAIRING

        val connectionWatcher: ConnectionWatcherViewModel = hiltViewModel()
        val connectionState by connectionWatcher.connectionState.collectAsState()
        LaunchedEffect(connectionState, currentRoute) {
            if (connectionState is ConnectionState.Idle && currentRoute != null && currentRoute != Routes.PAIRING) {
                navController.navigate(Routes.PAIRING) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                }
            }
        }

        Scaffold(
            containerColor = palette.background,
            bottomBar = {
                if (showBottomBar) {
                    RimBottomBar(
                        currentRoute = backStackEntry?.destination?.hierarchy,
                        onSelect = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
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
                composable(Routes.READINGS) { ReadingsScreen(showObis = showObis) }
                composable(Routes.NETWORK) { NetworkScreen(showObis = showObis) }
                composable(Routes.INFO) { InfoScreen(showObis = showObis) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        showObis = showObis,
                        onShowObisChange = appSettings::setShowObis,
                        dark = dark,
                        onDarkChange = appSettings::setDarkTheme,
                    )
                }
            }
        }
    }
}

@Composable
private fun RimBottomBar(
    currentRoute: Sequence<androidx.navigation.NavDestination>?,
    onSelect: (String) -> Unit,
) {
    val palette = RimTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.navBackground)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        bottomTabs.forEach { tab ->
            val active = currentRoute?.any { it.route == tab.route } == true
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) RimPrimaryDeep.copy(alpha = if (palette.dark) 0.20f else 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(tab.route) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) RimViolet else palette.textFaint,
                    modifier = Modifier.padding(0.dp),
                )
                Text(
                    tab.label,
                    color = if (active) RimViolet else palette.textFaint,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

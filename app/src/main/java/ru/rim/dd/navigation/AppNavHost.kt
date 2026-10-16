package ru.rim.dd.navigation

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.rim.dd.core.model.ConnectionState
import ru.rim.dd.feature.info.InfoScreen
import ru.rim.dd.feature.journal.JournalScreen
import ru.rim.dd.feature.network.NetworkScreen
import ru.rim.dd.feature.pairing.PairingScreen
import ru.rim.dd.feature.readings.ReadingsScreen
import ru.rim.dd.feature.settings.AppSettingsViewModel
import ru.rim.dd.feature.settings.SettingsScreen
import ru.rim.dd.ui.theme.RimError
import ru.rim.dd.ui.theme.RimTheme

private object Routes {
    const val PAIRING = "pairing"
    const val READINGS = "readings"
    const val NETWORK = "network"
    const val INFO = "info"
    const val SETTINGS = "settings"
    const val JOURNAL = "journal"
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.READINGS, "Показания", Icons.Filled.List),
    BottomTab(Routes.NETWORK, "Сеть", Icons.Filled.Wifi),
    BottomTab(Routes.INFO, "Инфо", Icons.Filled.Info),
    BottomTab(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

/** Тема и showObis — глобальные настройки, поэтому RimTheme оборачивает всё дерево здесь. Экран подключения — без нижней панели. */
@Composable
fun AppNavHost() {
    val appSettings: AppSettingsViewModel = hiltViewModel()
    val dark by appSettings.darkTheme.collectAsState()
    val showObis by appSettings.showObis.collectAsState()

    RimTheme(dark = dark) {
        val palette = RimTheme.palette
        // Фон окна под вырезом/скрытой шторкой по умолчанию белый (светлая системная тема) —
        // красим его в цвет текущей темы приложения, чтобы не было светлой полосы сверху.
        val hostView = LocalView.current
        SideEffect {
            (hostView.context as? Activity)?.window
                ?.setBackgroundDrawable(ColorDrawable(palette.background.toArgb()))
        }
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

        // «Назад» при подключённом приборе означает «выйти»: разрываем связь и уходим на экран подключения.
        val leaveSession: () -> Unit = {
            connectionWatcher.disconnect()
            navController.navigate(Routes.PAIRING) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            }
        }
        // Связь пропала, но сеанс ещё не закрыт (идёт переподключение) — показываем плашку.
        val linkLost = currentRoute != null && currentRoute != Routes.PAIRING &&
                connectionState !is ConnectionState.Connected && connectionState !is ConnectionState.Idle

        Scaffold(
            containerColor = palette.background,
            bottomBar = {
                if (showBottomBar) {
                    RimBottomBar(
                        currentRoute = backStackEntry?.destination?.hierarchy,
                        forceRoute = if (currentRoute == Routes.JOURNAL) Routes.SETTINGS else null,
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
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (linkLost) {
                    Text(
                        "Нет связи с прибором — идёт переподключение…",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(RimError)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = Routes.PAIRING,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(Routes.PAIRING) {
                        PairingScreen(onConnected = { navController.navigate(Routes.READINGS) })
                    }
                    composable(Routes.READINGS) {
                        BackHandler(onBack = leaveSession)
                        ReadingsScreen(showObis = showObis)
                    }
                    composable(Routes.NETWORK) {
                        BackHandler(onBack = leaveSession)
                        NetworkScreen(showObis = showObis)
                    }
                    composable(Routes.INFO) {
                        BackHandler(onBack = leaveSession)
                        InfoScreen(showObis = showObis)
                    }
                    composable(Routes.SETTINGS) {
                        BackHandler(onBack = leaveSession)
                        SettingsScreen(
                            showObis = showObis,
                            onShowObisChange = appSettings::setShowObis,
                            dark = dark,
                            onDarkChange = appSettings::setDarkTheme,
                            onOpenJournal = { navController.navigate(Routes.JOURNAL) },
                        )
                    }
                    composable(Routes.JOURNAL) {
                        // «Назад» из журнала возвращает в настройки, а не завершает сеанс.
                        BackHandler { navController.popBackStack() }
                        JournalScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

@Composable
private fun RimBottomBar(
    currentRoute: Sequence<NavDestination>?,
    forceRoute: String?,
    onSelect: (String) -> Unit,
) {
    val palette = RimTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.navBackground)
            .drawBehind { drawRect(palette.border, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) },
    ) {
        bottomTabs.forEach { tab ->
            val active = forceRoute == tab.route || currentRoute?.any { it.route == tab.route } == true
            Column(
                modifier = Modifier
                    .weight(1f)
                    // Активная вкладка — фирменная полоса 3 dp сверху, без подложки и скруглений.
                    .drawBehind {
                        if (active) drawRect(palette.accent, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                    }
                    .clickable { onSelect(tab.route) }
                    .padding(top = 10.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) palette.accentText else palette.textSecondary,
                )
                Text(
                    tab.label,
                    color = if (active) palette.accentText else palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

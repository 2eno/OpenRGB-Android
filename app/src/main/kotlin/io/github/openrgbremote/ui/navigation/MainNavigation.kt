package io.github.openrgbremote.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.openrgbremote.R
import io.github.openrgbremote.feature.color.ColorRoute as ColorScreenRoute
import io.github.openrgbremote.feature.devicedetail.DeviceDetailRoute as DeviceDetailScreenRoute
import io.github.openrgbremote.feature.devices.DevicesRoute as DevicesScreenRoute
import io.github.openrgbremote.feature.profiles.ProfileEditRoute as ProfileEditScreenRoute
import io.github.openrgbremote.feature.profiles.ProfilesRoute as ProfilesScreenRoute
import io.github.openrgbremote.feature.servers.ScanRoute as ScanScreenRoute
import io.github.openrgbremote.feature.servers.ServerEditRoute as ServerEditScreenRoute
import io.github.openrgbremote.feature.servers.ServersRoute as ServersScreenRoute
import io.github.openrgbremote.feature.settings.SettingsRoute as SettingsScreenRoute

private enum class TopLevel(
    val route: Any,
    @StringRes val label: Int,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    DEVICES(DevicesRoute, R.string.nav_devices, Icons.Filled.Lightbulb, Icons.Outlined.Lightbulb),
    PROFILES(ProfilesRoute, R.string.nav_profiles, Icons.Filled.Style, Icons.Outlined.Style),
    SETTINGS(SettingsRoute, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

private fun NavDestination?.topLevel(): TopLevel? = when {
    this == null -> null
    hasRoute(DevicesRoute::class) || hasRoute(DeviceDetailRoute::class) || hasRoute(ColorRoute::class) -> TopLevel.DEVICES
    hasRoute(ProfilesRoute::class) || hasRoute(ProfileEditRoute::class) -> TopLevel.PROFILES
    else -> TopLevel.SETTINGS
}

/**
 * Main navigation: bottom bar on phones, navigation rail on tablets/landscape
 * (handled by [NavigationSuiteScaffold] based on the window size class).
 */
@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination.topLevel()

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevel.entries.forEach { destination ->
                val selected = destination == current
                item(
                    selected = selected,
                    onClick = {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = DevicesRoute) {
            composable<DevicesRoute> {
                DevicesScreenRoute(
                    onOpenDevice = { key -> navController.navigate(DeviceDetailRoute(key)) },
                    onScan = { navController.navigate(ScanRoute) },
                    onAddManually = { navController.navigate(ServerEditRoute()) },
                    onColorAll = { navController.navigate(ColorRoute()) },
                )
            }
            composable<DeviceDetailRoute> {
                DeviceDetailScreenRoute(
                    onBack = navController::navigateUp,
                    onOpenColor = { key, zone, led -> navController.navigate(ColorRoute(key, zone, led)) },
                )
            }
            composable<ColorRoute> {
                ColorScreenRoute(onBack = navController::navigateUp)
            }
            composable<ProfilesRoute> {
                ProfilesScreenRoute(onEdit = { id -> navController.navigate(ProfileEditRoute(id)) })
            }
            composable<ProfileEditRoute> {
                ProfileEditScreenRoute(onDone = navController::navigateUp)
            }
            composable<SettingsRoute> {
                SettingsScreenRoute(onOpenServers = { navController.navigate(ServersRoute) })
            }
            composable<ServersRoute> {
                ServersScreenRoute(
                    onBack = navController::navigateUp,
                    onEdit = { id -> navController.navigate(ServerEditRoute(serverId = id)) },
                    onScan = { navController.navigate(ScanRoute) },
                    onAddManually = { navController.navigate(ServerEditRoute()) },
                )
            }
            composable<ServerEditRoute> {
                ServerEditScreenRoute(onDone = navController::navigateUp)
            }
            composable<ScanRoute> {
                ScanScreenRoute(
                    onBack = navController::navigateUp,
                    onSelect = { server ->
                        navController.navigate(ServerEditRoute(host = server.host, port = server.port, name = server.suggestedName))
                    },
                    onAddManually = { navController.navigate(ServerEditRoute()) },
                )
            }
        }
    }
}

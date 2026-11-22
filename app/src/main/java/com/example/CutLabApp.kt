package com.example

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeViewModel
import com.example.feature.mediaimport.MediaVaultScreen
import com.example.feature.mediaimport.MediaVaultViewModel
import com.example.feature.project.ProjectWorkspaceScreen
import com.example.feature.project.ProjectWorkspaceViewModel
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object MediaVaultRoute

@Serializable
object SettingsRoute

@Serializable
data class ProjectWorkspaceRoute(val projectId: String)

private data class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(
        route = HomeRoute,
        label = "Projects",
        selectedIcon = Icons.Filled.FolderCopy,
        unselectedIcon = Icons.Outlined.FolderCopy,
        testTag = "nav_projects",
    ),
    TopLevelDestination(
        route = MediaVaultRoute,
        label = "Media",
        selectedIcon = Icons.Filled.PermMedia,
        unselectedIcon = Icons.Outlined.PermMedia,
        testTag = "nav_media",
    ),
    TopLevelDestination(
        route = SettingsRoute,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings",
    ),
)

@Composable
fun CutLabApp(
    appContainer: AppContainer,
    windowSizeClass: WindowSizeClass,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isInWorkspace = currentDestination?.hasRoute<ProjectWorkspaceRoute>() == true
    val useNavigationRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    val navigateTopLevel: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!useNavigationRail && !isInWorkspace) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    windowInsets = WindowInsets.navigationBars,
                ) {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                            modifier = Modifier.testTag(dest.testTag),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (useNavigationRail && !isInWorkspace) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    topLevelDestinations.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(dest.route::class)
                        } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                            modifier = Modifier.testTag(dest.testTag),
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                composable<HomeRoute> {
                    val vm: HomeViewModel = viewModel(
                        factory = HomeViewModel.factory(
                            projectRepository = appContainer.projectRepository,
                            preferencesRepository = appContainer.preferencesRepository,
                        ),
                    )
                    HomeScreen(
                        viewModel = vm,
                        onOpenProject = { projectId ->
                            navController.navigate(ProjectWorkspaceRoute(projectId))
                        },
                    )
                }

                composable<MediaVaultRoute> {
                    val vm: MediaVaultViewModel = viewModel(
                        factory = MediaVaultViewModel.factory(appContainer.projectRepository),
                    )
                    MediaVaultScreen(viewModel = vm)
                }

                composable<SettingsRoute> {
                    val vm: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            preferencesRepository = appContainer.preferencesRepository,
                            projectRepository = appContainer.projectRepository,
                            performanceMonitor = appContainer.performanceMonitor,
                            exportCoordinator = appContainer.exportCoordinator,
                        ),
                    )
                    SettingsScreen(viewModel = vm)
                }

                composable<ProjectWorkspaceRoute> { entry ->
                    val route = entry.toRoute<ProjectWorkspaceRoute>()
                    val vm: ProjectWorkspaceViewModel = viewModel(
                        key = "workspace_${route.projectId}",
                        factory = ProjectWorkspaceViewModel.factory(
                            projectId = route.projectId,
                            projectRepository = appContainer.projectRepository,
                            preferencesRepository = appContainer.preferencesRepository,
                            pcmAudioExtractor = appContainer.pcmAudioExtractor,
                            mixedAudioPreviewPlayer = appContainer.mixedAudioPreviewPlayer,
                            exportPlanner = appContainer.exportPlanner,
                            exportOutputManager = appContainer.exportOutputManager,
                            exportCoordinator = appContainer.exportCoordinator,
                            proxyMediaManager = appContainer.proxyMediaManager,
                            appContext = appContainer.applicationContext,
                        ),
                    )
                    ProjectWorkspaceScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

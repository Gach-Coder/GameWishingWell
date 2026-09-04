package com.gamewishingwell.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gamewishingwell.ui.chat.ChatScreen
import com.gamewishingwell.ui.files.FileBrowserScreen
import com.gamewishingwell.ui.game.GameScreen
import com.gamewishingwell.ui.home.HomeScreen
import com.gamewishingwell.ui.settings.SettingsScreen

@Composable
fun WishwellApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // chat?gameId={gameId} 这类带参数的路由要用前缀匹配，否则创作页底部导航会消失
    val showBottomBar = currentRoute == "home" || currentRoute == "settings" || currentRoute?.startsWith("chat") == true

    Scaffold(
        // 顶栏 inset 由各页面自己的 TopAppBar 处理；游戏页无 TopAppBar，
        // 置零后 WebView 全屏铺到状态栏下，消除游戏画面顶部的留白条。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    // 不用 saveState/restoreState：避免恢复旧会话导致全局 agent 会话串扰，
                    // 每次进入 tab 都重新初始化 ViewModel（创作页会重新加载对应会话）
                    NavigationBarItem(
                        selected = currentRoute == "home",
                        onClick = { navController.navigate("home") { popUpTo("home") { inclusive = false }; launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("我的游戏") }
                    )
                    NavigationBarItem(
                        selected = currentRoute?.startsWith("chat") == true,
                        onClick = {
                            // 每次点击"创作"都重建 chat 目的地，保证进入的是空会话而不是旧对话
                            navController.navigate("chat") { popUpTo("home") { inclusive = false } }
                        },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        label = { Text("创作") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { popUpTo("home") { inclusive = false }; launchSingleTop = true } },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    onOpenGame = { id -> navController.navigate("game?source=game&gameId=$id") },
                    onEditGame = { id -> navController.navigate("chat?gameId=$id") },
                    onNewChat = { navController.navigate("chat") },
                    onContinueDraft = { navController.navigate("chat?draft=true") },
                    onOpenFiles = { id -> navController.navigate("files?gameId=$id") }
                )
            }
            composable(
                route = "chat?gameId={gameId}&draft={draft}",
                arguments = listOf(
                    navArgument("gameId") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("draft") { type = NavType.BoolType; defaultValue = false }
                )
            ) { entry ->
                val gameId = entry.arguments?.getLong("gameId")?.takeIf { it > 0 }
                val resumeDraft = entry.arguments?.getBoolean("draft") ?: false
                ChatScreen(
                    gameId = gameId,
                    resumeDraft = resumeDraft,
                    onPlay = {
                        // 编辑已保存游戏时从游戏目录加载最新版本，草稿模式加载草稿
                        if (gameId != null) {
                            navController.navigate("game?source=game&gameId=$gameId")
                        } else {
                            navController.navigate("game?source=draft&gameId=-1")
                        }
                    },
                    onOpenSettings = { navController.navigate("settings") },
                    onSaved = { navController.popBackStack("home", false) }
                )
            }
            composable(
                route = "game?source={source}&gameId={gameId}",
                arguments = listOf(
                    navArgument("source") { type = NavType.StringType; defaultValue = "draft" },
                    navArgument("gameId") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { entry ->
                val source = entry.arguments?.getString("source") ?: "draft"
                val gameId = entry.arguments?.getLong("gameId") ?: -1L
                GameScreen(
                    source = source,
                    gameId = gameId,
                    onBack = { navController.popBackStack() },
                    onEdit = { id ->
                        if (id != null && id > 0) {
                            navController.navigate("chat?gameId=$id") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    onHome = { navController.popBackStack("home", false) }
                )
            }
            composable(
                route = "files?gameId={gameId}",
                arguments = listOf(
                    navArgument("gameId") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { entry ->
                val gameId = entry.arguments?.getLong("gameId") ?: -1L
                FileBrowserScreen(
                    gameId = gameId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}

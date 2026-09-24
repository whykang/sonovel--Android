package com.wang.sonovel.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wang.sonovel.ExternalEvent
import com.wang.sonovel.graph
import com.wang.sonovel.ui.screens.AboutScreen
import com.wang.sonovel.ui.screens.BatchScreen
import com.wang.sonovel.ui.screens.BookDetailScreen
import com.wang.sonovel.ui.screens.LibraryScreen
import com.wang.sonovel.ui.screens.LinkDialog
import com.wang.sonovel.ui.screens.ReaderScreen
import com.wang.sonovel.ui.screens.SearchScreen
import com.wang.sonovel.ui.screens.SettingsScreen
import com.wang.sonovel.ui.screens.SourcesScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    SEARCH("search", "搜索", Icons.Outlined.Search),
    LIBRARY("library", "书架", Icons.AutoMirrored.Outlined.LibraryBooks),
    SOURCES("sources", "书源", Icons.Outlined.Hub),
    SETTINGS("settings", "设置", Icons.Outlined.Settings),
}

object Routes {
    fun book(key: String?, url: String, name: String = "", author: String = "") =
        "book?key=${Uri.encode(key.orEmpty())}&url=${Uri.encode(url)}&name=${Uri.encode(name)}&author=${Uri.encode(author)}"

    fun reader(path: String) = "reader?path=${Uri.encode(path)}"
}

@Composable
fun AppRoot(events: MutableStateFlow<ExternalEvent?>) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    var linkDialogUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val event by events.collectAsStateWithLifecycle()

    LaunchedEffect(event) {
        when (val e = event) {
            is ExternalEvent.SharedUrl -> {
                val rule = context.graph.rules.matchByUrl(e.url)
                if (rule != null) nav.navigate(Routes.book(rule.key, e.url)) else linkDialogUrl = e.url
            }
            is ExternalEvent.OpenBook -> {
                tab = Tab.LIBRARY
                nav.popBackStack("home", inclusive = false)
                nav.navigate(Routes.reader(e.path))
            }
            is ExternalEvent.OpenTab -> {
                Tab.entries.firstOrNull { it.route == e.tab }?.let { tab = it }
                nav.popBackStack("home", inclusive = false)
            }
            null -> Unit
        }
        events.value = null
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        NavHost(navController = nav, startDestination = "home") {
            composable("home") {
                HomeScaffold(nav, tab, onTab = { tab = it }, snackbar = snackbar, onLink = { linkDialogUrl = "" })
            }
            composable(
                "book?key={key}&url={url}&name={name}&author={author}",
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType; defaultValue = "" },
                    navArgument("url") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("author") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val a = entry.arguments!!
                BookDetailScreen(
                    ruleKey = a.getString("key").orEmpty().ifBlank { null },
                    url = a.getString("url").orEmpty(),
                    initialName = a.getString("name").orEmpty(),
                    initialAuthor = a.getString("author").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenLibrary = {
                        tab = Tab.LIBRARY
                        nav.popBackStack("home", inclusive = false)
                    },
                )
            }
            composable("batch") {
                BatchScreen(onBack = { nav.popBackStack() }, onOpenLibrary = {
                    tab = Tab.LIBRARY
                    nav.popBackStack("home", inclusive = false)
                })
            }
            composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable(
                "reader?path={path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                ReaderScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }

    linkDialogUrl?.let { initial ->
        LinkDialog(
            initial = initial,
            onDismiss = { linkDialogUrl = null },
            onOpen = { key, url ->
                linkDialogUrl = null
                nav.navigate(Routes.book(key, url))
            },
        )
    }
}

@Composable
private fun HomeScaffold(
    nav: NavHostController,
    tab: Tab,
    onTab: (Tab) -> Unit,
    snackbar: SnackbarHostState,
    onLink: () -> Unit,
) {
    val context = LocalContext.current
    val tasks by context.graph.downloads.tasks.collectAsStateWithLifecycle()
    val activeCount = tasks.count { it.status.active }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = {
                            if (t == Tab.LIBRARY && activeCount > 0) {
                                BadgedBox(badge = { Badge { Text("$activeCount") } }) { Icon(t.icon, null) }
                            } else Icon(t.icon, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.SEARCH -> SearchScreen(
                        onOpenBook = { r -> nav.navigate(Routes.book(r.sourceKey, r.url, r.bookName, r.author.orEmpty())) },
                        onBatch = { nav.navigate("batch") },
                        onLink = onLink,
                    )
                    Tab.LIBRARY -> LibraryScreen(onRead = { nav.navigate(Routes.reader(it.absolutePath)) })
                    Tab.SOURCES -> SourcesScreen()
                    Tab.SETTINGS -> SettingsScreen(onAbout = { nav.navigate("about") })
                }
            }
        }
    }
}

/**
 * 下载前申请必要权限（通知、Android 9 及以下的存储权限），无论是否授予都继续执行
 */
@Composable
fun rememberDownloadPermissions(): (onReady: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke()
        pending = null
    }
    return remember(launcher) {
        { onReady ->
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT < 29 && context.graph.settings.current.saveToPublic) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (perms.isEmpty()) onReady() else {
                pending = onReady
                launcher.launch(perms.toTypedArray())
            }
        }
    }
}

/** 显示 Snackbar 的便捷方法 */
@Composable
fun rememberSnack(): (String) -> Unit {
    val host = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(host) { { msg -> scope.launch { host.currentSnackbarData?.dismiss(); host.showSnackbar(msg) } } }
}

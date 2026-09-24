package com.wang.sonovel.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wang.sonovel.BuildConfig
import com.wang.sonovel.R
import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.LangType
import com.wang.sonovel.data.ThemeMode
import com.wang.sonovel.graph
import com.wang.sonovel.ui.components.ChoiceDialog
import com.wang.sonovel.ui.components.ConfirmDialog
import com.wang.sonovel.ui.components.InputDialog
import com.wang.sonovel.ui.components.SectionTitle
import com.wang.sonovel.ui.components.SettingItem
import com.wang.sonovel.ui.components.SwitchItem
import com.wang.sonovel.ui.components.formatSize
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface SettingDialog {
    data class Choice<T>(
        val title: String, val options: List<T>, val selected: T, val label: (T) -> String, val onSelect: (T) -> Unit,
    ) : SettingDialog

    data class Input(
        val title: String, val initial: String, val hint: String? = null, val numeric: Boolean = false,
        val validate: (String) -> String? = { null }, val onConfirm: (String) -> Unit,
    ) : SettingDialog
}

private fun intValidator(min: Int, max: Int): (String) -> String? = { s ->
    val v = s.toIntOrNull()
    if (v == null || v < min || v > max) "请输入 $min ~ $max 之间的整数" else null
}

@Composable
fun SettingsScreen(onAbout: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val s by g.settings.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingDialog?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val snack = rememberSnack()
    fun set(block: (AppSettings) -> AppSettings) = g.settings.update(block)

    val chapterCache = File(context.cacheDir, "chapters")
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { chapterCache.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))

        SectionTitle("外观")
        SettingItem("主题", s.themeMode.label, Icons.Outlined.DarkMode, onClick = {
            dialog = SettingDialog.Choice("主题", ThemeMode.entries, s.themeMode, { it.label }) { v -> set { it.copy(themeMode = v) } }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchItem("动态取色", "跟随系统壁纸颜色（Android 12+）", s.dynamicColor, Icons.Outlined.Palette) { v -> set { it.copy(dynamicColor = v) } }
        }

        SectionTitle("下载")
        SettingItem("默认格式", s.format.label, Icons.Outlined.Description, onClick = {
            dialog = SettingDialog.Choice("默认格式", ExportFormat.entries, s.format, { it.label }) { v -> set { it.copy(extName = v.ext) } }
        })
        SettingItem("TXT 编码", s.txtEncoding + if (s.txtEncoding == "GBK") "（兼容旧设备）" else "", Icons.Outlined.TextFields, onClick = {
            dialog = SettingDialog.Choice("TXT 编码", listOf("UTF-8", "GBK"), s.txtEncoding, { it }) { v -> set { it.copy(txtEncoding = v) } }
        })
        SwitchItem("保存副本到 下载/SoNovel", "下载完成后自动复制到公共下载目录，方便其他应用访问", s.saveToPublic, Icons.Outlined.FolderOpen) { v ->
            set { it.copy(saveToPublic = v) }
        }
        SwitchItem("获取高清封面", "从起点、纵横、七猫匹配更清晰的封面", s.fetchBetterCover, Icons.Outlined.Image) { v ->
            set { it.copy(fetchBetterCover = v) }
        }
        SettingItem("内容语言", LangType.label(LangType.normalize(s.language)) + "（当前：${LangType.label(s.targetLanguage)}）", Icons.Outlined.Language, onClick = {
            val opts = listOf("", LangType.ZH_CN, LangType.ZH_HANT, LangType.ZH_TW)
            dialog = SettingDialog.Choice("内容语言（简繁转换）", opts, LangType.normalize(s.language) ?: "", { LangType.label(it.ifEmpty { null }) }) { v ->
                set { it.copy(language = v) }
            }
        })

        SectionTitle("搜索")
        SettingItem("每个书源显示条数", if (s.searchLimit <= 0) "全部" else "前 ${s.searchLimit} 条", Icons.AutoMirrored.Outlined.ViewList, onClick = {
            dialog = SettingDialog.Input("每个书源显示条数", s.searchLimit.toString(), "0 表示不限制", true, intValidator(0, 500)) { v ->
                set { it.copy(searchLimit = v.toInt()) }
            }
        })
        SwitchItem("过滤低相似度结果", "聚合搜索时隐藏与关键字相似度过低的结果", s.searchFilter, Icons.Outlined.FilterAlt) { v -> set { it.copy(searchFilter = v) } }

        SectionTitle("抓取")
        SettingItem("并发上限", if (s.concurrency <= 0) "自动（最多 50）" else "${s.concurrency}", Icons.Outlined.Speed, onClick = {
            dialog = SettingDialog.Input("并发上限", if (s.concurrency <= 0) "0" else s.concurrency.toString(), "0 表示自动；书源规则中的推荐值优先", true, intValidator(0, 100)) { v ->
                set { it.copy(concurrency = v.toInt().let { n -> if (n <= 0) -1 else n }) }
            }
        })
        SettingItem("请求间隔", "${s.minInterval} ~ ${s.maxInterval} 毫秒（随机）", Icons.Outlined.Timer, onClick = {
            dialog = SettingDialog.Input(
                "请求间隔（毫秒）", "${s.minInterval}-${s.maxInterval}", "格式：最小-最大，例如 200-400", false,
                { t -> val p = t.split('-').mapNotNull { it.trim().toIntOrNull() }; if (p.size != 2 || p[0] < 0 || p[1] < p[0]) "格式错误" else null },
            ) { v ->
                val p = v.split('-').map { it.trim().toInt() }
                set { it.copy(minInterval = p[0], maxInterval = p[1]) }
            }
        })
        SwitchItem("失败重试", if (s.enableRetry) "失败章节最多重试 ${s.maxRetries} 次" else "关闭后任一章节失败即中断下载", s.enableRetry, Icons.Outlined.Replay) { v ->
            set { it.copy(enableRetry = v) }
        }
        SettingItem("最大重试次数", "${s.maxRetries} 次", null, enabled = s.enableRetry, onClick = {
            dialog = SettingDialog.Input("最大重试次数", s.maxRetries.toString(), null, true, intValidator(1, 20)) { v -> set { it.copy(maxRetries = v.toInt()) } }
        })
        SettingItem("重试间隔", "${s.retryMinInterval} ~ ${s.retryMaxInterval} 毫秒（逐次递增）", null, enabled = s.enableRetry, onClick = {
            dialog = SettingDialog.Input(
                "重试间隔（毫秒）", "${s.retryMinInterval}-${s.retryMaxInterval}", "格式：最小-最大", false,
                { t -> val p = t.split('-').mapNotNull { it.trim().toIntOrNull() }; if (p.size != 2 || p[0] < 0 || p[1] < p[0]) "格式错误" else null },
            ) { v ->
                val p = v.split('-').map { it.trim().toInt() }
                set { it.copy(retryMinInterval = p[0], retryMaxInterval = p[1]) }
            }
        })

        SectionTitle("网络")
        SwitchItem("HTTP 代理", if (s.proxyEnabled) "${s.proxyHost}:${s.proxyPort}" else "用于需要代理的书源", s.proxyEnabled, Icons.Outlined.Router) { v ->
            set { it.copy(proxyEnabled = v) }
        }
        SettingItem("代理地址", "${s.proxyHost}:${s.proxyPort}", null, onClick = {
            dialog = SettingDialog.Input(
                "代理地址", "${s.proxyHost}:${s.proxyPort}", "格式：主机:端口，例如 127.0.0.1:7890", false,
                { t -> if (Regex("^[^:\\s]+:\\d{1,5}$").matches(t.trim())) null else "格式错误" },
            ) { v -> set { it.copy(proxyHost = v.substringBeforeLast(':'), proxyPort = v.substringAfterLast(':').toInt()) } }
        })
        SettingItem("Cloudflare 绕过服务", s.cfBypass.ifBlank { "未设置（CloudflareBypassForScraping 服务地址）" }, Icons.Outlined.Cloud, onClick = {
            dialog = SettingDialog.Input("cf-bypass 地址", s.cfBypass, "例如 http://192.168.1.10:8000，留空表示不使用") { v -> set { it.copy(cfBypass = v) } }
        })
        SettingItem("起点 Cookie", if (s.qidianCookie.isBlank()) "未设置（填写 w_tsfp=xxx 以获取起点最新封面）" else "已设置", Icons.Outlined.Cookie, onClick = {
            dialog = SettingDialog.Input("起点 Cookie", s.qidianCookie, "w_tsfp=xxx") { v -> set { it.copy(qidianCookie = v) } }
        })
        SwitchItem("忽略 SSL 证书错误", "仅在书源证书异常时开启", s.ignoreSsl, Icons.Outlined.Lock) { v -> set { it.copy(ignoreSsl = v) } }

        SectionTitle("其他")
        SettingItem("清除章节缓存", "未完成或有失败章节的下载会保留缓存以便续传 · ${formatSize(cacheSize)}", Icons.Outlined.DeleteOutline, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { chapterCache.deleteRecursively() }
                cacheSize = 0
                snack("已清除章节缓存")
            }
        })
        SettingItem("恢复默认设置", null, Icons.Outlined.RestartAlt, onClick = { confirmReset = true })
        SettingItem("关于", "版本 ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info, onClick = onAbout)
        Spacer(Modifier.height(24.dp))
    }

    when (val d = dialog) {
        is SettingDialog.Choice<*> -> {
            @Suppress("UNCHECKED_CAST")
            val c = d as SettingDialog.Choice<Any?>
            ChoiceDialog(c.title, c.options, c.selected, c.label, onDismiss = { dialog = null }, onSelect = c.onSelect)
        }
        is SettingDialog.Input -> InputDialog(
            d.title, d.initial, d.hint, d.numeric, validate = d.validate, onDismiss = { dialog = null }, onConfirm = d.onConfirm,
        )
        null -> Unit
    }

    if (confirmReset) {
        ConfirmDialog("恢复默认设置？", "除外观设置外，所有设置项将恢复为默认值。", onDismiss = { confirmReset = false }, onConfirm = {
            g.settings.reset()
            snack("已恢复默认设置")
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("关于") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Image(
                painterResource(R.drawable.ic_launcher_foreground), null,
                Modifier.size(88.dp).clip(RoundedCornerShape(22.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF1F6F5C)),
            )
            Spacer(Modifier.height(12.dp))
            Text("So Novel", style = MaterialTheme.typography.headlineMedium)
            Text("版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "So Novel 安卓版是开源项目 freeok/so-novel 的原生移植，提供聚合搜索、书源规则、批量下载，以及 EPUB / TXT / HTML / PDF 导出等完整功能。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "免责声明：本应用仅供学习与技术交流使用，所有内容均来自第三方网站，与本应用无关。请支持正版阅读，勿将下载内容用于商业用途。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingItem("原项目主页", "github.com/freeok/so-novel", Icons.Outlined.Code, onClick = { open("https://github.com/freeok/so-novel") })
            SettingItem("书源说明", "各规则文件的适用场景与注意事项", Icons.Outlined.Description, onClick = {
                open("https://github.com/freeok/so-novel/blob/main/BOOK_SOURCES.md")
            })
            SettingItem("推荐阅读器", "EPUB 推荐 Koodo Reader、Readest、静读天下 等", Icons.Outlined.Info)
            Row(Modifier.navigationBarsPadding().height(24.dp)) {}
        }
    }
}

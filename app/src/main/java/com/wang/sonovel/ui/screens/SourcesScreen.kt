package com.wang.sonovel.ui.screens

import android.app.Application
import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ToggleOff
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.core.Misc
import com.wang.sonovel.data.Rule
import com.wang.sonovel.data.RuleFile
import com.wang.sonovel.data.SourceStatus
import com.wang.sonovel.graph
import com.wang.sonovel.ui.components.ImmersiveSheetEffect
import com.wang.sonovel.ui.components.ConfirmDialog
import com.wang.sonovel.ui.components.Pill
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    /** key -> 连通性（null 表示检测中） */
    val status = MutableStateFlow<Map<String, SourceStatus?>>(emptyMap())
    val checking = MutableStateFlow(false)

    fun check(rules: List<Rule>) {
        if (checking.value) return
        checking.value = true
        status.update { it + rules.associate { r -> r.key to null } }
        viewModelScope.launch {
            rules.map { r ->
                launch {
                    val s = Misc.checkSource(r, g.settings.current)
                    status.update { it + (r.key to s) }
                }
            }.joinAll()
            checking.value = false
        }
    }
}

private enum class SourceFilter(val label: String) { ALL("全部"), ON("已开启"), OFF("已关闭") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: SourcesViewModel = viewModel()) {
    val context = LocalContext.current
    val g = context.graph
    val files by g.rules.files.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(SourceFilter.ALL) }
    var detail by remember { mutableStateOf<Rule?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showTemplate by remember { mutableStateOf(false) }
    var confirmDeleteFile by remember { mutableStateOf<RuleFile?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val snack = rememberSnack()
    val scope = rememberCoroutineScope()

    // 所有规则文件合并为一个列表；settings 变化时重新计算开关状态
    val all = remember(files) { files.flatMap { it.rules } }
    val enabledMap = remember(all, settings.sourceStates) { all.associate { it.key to g.rules.isEnabled(it) } }
    val onCount = enabledMap.count { it.value }
    val builtInFiles = remember(files) { files.filter { it.builtIn && !it.overridesBuiltIn }.map { it.name }.toSet() }
    val searchableOn = all.count { it.searchable && enabledMap[it.key] == true }
    val shown = when (filter) {
        SourceFilter.ALL -> all
        SourceFilter.ON -> all.filter { enabledMap[it.key] == true }
        SourceFilter.OFF -> all.filter { enabledMap[it.key] != true }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "custom.json"
                    val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    val n = g.rules.import(name, text)
                    "已导入 $n 个书源，默认已开启"
                }.getOrElse { "导入失败：${it.message}" }
            }
            snack(msg)
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("书源", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "已开启 $onCount / 共 ${all.size} 个 · 搜索时使用已开启的 $searchableOn 个书源",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.check(shown) }, enabled = !checking && shown.isNotEmpty()) {
                if (checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.NetworkCheck, "检测连通性")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("导入规则文件（.json）") },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                        onClick = { menu = false; importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                    )
                    DropdownMenuItem(
                        text = { Text("查看规则模板") },
                        leadingIcon = { Icon(Icons.Outlined.Description, null) },
                        onClick = { menu = false; showTemplate = true },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (filter == SourceFilter.ALL) "全部开启" else "开启列表中的书源") },
                        leadingIcon = { Icon(Icons.Outlined.ToggleOn, null) },
                        onClick = { menu = false; g.rules.setAllEnabled(shown, true) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (filter == SourceFilter.ALL) "全部关闭" else "关闭列表中的书源") },
                        leadingIcon = { Icon(Icons.Outlined.ToggleOff, null) },
                        onClick = { menu = false; g.rules.setAllEnabled(shown, false) },
                    )
                    DropdownMenuItem(
                        text = { Text("恢复默认开关") },
                        leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                        onClick = { menu = false; confirmReset = true },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceFilter.entries.forEach { f ->
                val n = when (f) {
                    SourceFilter.ALL -> all.size
                    SourceFilter.ON -> onCount
                    SourceFilter.OFF -> all.size - onCount
                }
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} $n") })
            }
        }

        if (shown.isEmpty()) {
            Text(
                if (filter == SourceFilter.ON) "还没有开启的书源，搜索将没有结果" else "没有书源",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.key }) { r ->
                SourceRow(
                    rule = r,
                    builtIn = r.file in builtInFiles,
                    enabled = enabledMap[r.key] == true,
                    status = status[r.key],
                    checked = status.containsKey(r.key),
                    onToggle = { g.rules.setEnabled(r, it) },
                    onClick = { detail = r },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    detail?.let { r ->
        val json = remember(r) { g.rules.rawJson(r) }
        val ruleFile = files.firstOrNull { it.name == r.file }
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ImmersiveSheetEffect()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(r.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(checked = enabledMap[r.key] == true, onCheckedChange = { g.rules.setEnabled(r, it) })
                }
                Text(
                    "来源：" + (if (ruleFile?.builtIn == true && !ruleFile.overridesBuiltIn) "内置" else "导入") + " · ${r.file}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                sourceNote(r)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                r.comment?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("rule", json))
                        snack("已复制规则 JSON")
                    }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("复制 JSON")
                    }
                    OutlinedButton(onClick = { vm.check(listOf(r)) }) { Text("检测连通性") }
                    if (ruleFile != null && (!ruleFile.builtIn || ruleFile.overridesBuiltIn)) {
                        OutlinedButton(onClick = { confirmDeleteFile = ruleFile }) {
                            Text(if (ruleFile.builtIn) "恢复内置版本" else "删除导入的文件")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                    SelectionContainer {
                        Text(
                            json, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(16.dp))
            }
        }
    }

    if (showTemplate) {
        val template = remember {
            runCatching { context.assets.open("rule-template.json5").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        AlertDialog(
            onDismissRequest = { showTemplate = false },
            title = { Text("书源规则模板") },
            text = {
                SelectionContainer {
                    Text(
                        template, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("template", template))
                    snack("已复制模板")
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { showTemplate = false }) { Text("关闭") } },
        )
    }

    confirmDeleteFile?.let { f ->
        ConfirmDialog(
            title = if (f.builtIn) "恢复内置版本？" else "删除 ${f.name}？",
            text = if (f.builtIn) "将删除导入的同名文件，恢复为应用内置的规则。"
            else "将删除这个导入的规则文件，其中的 ${f.rules.size} 个书源都会被移除。",
            onDismiss = { confirmDeleteFile = null },
            onConfirm = {
                g.rules.deleteUserFile(f.name)
                detail = null
            },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "恢复默认开关？",
            text = "所有书源将恢复为默认的开启状态。",
            onDismiss = { confirmReset = false },
            onConfirm = { g.rules.resetStates() },
        )
    }
}

/** 书源的使用提示（来自原先的规则文件分类） */
private fun sourceNote(rule: Rule): String? = when (rule.file) {
    "proxy-required.json" -> "需要代理（非大陆 IP），部分需在设置中配置 cf-bypass"
    "rate-limit.json" -> "源站限流严重，建议并发 1~5"
    "cloudflare.json" -> "有 Cloudflare 保护，需在设置中配置 cf-bypass"
    "no-search.json" -> "不支持搜索，请用“链接下载”粘贴详情页地址"
    else -> null
}

/** 列表中显示的标签 */
private fun sourceTags(rule: Rule, builtIn: Boolean): List<String> = buildList {
    when (rule.file) {
        "proxy-required.json" -> add("需代理")
        "rate-limit.json" -> add("限流")
        "cloudflare.json" -> add("Cloudflare")
    }
    if (!builtIn) add("自定义")
    if (!rule.searchable) add("不支持搜索")
    if (rule.needProxy && rule.file != "proxy-required.json") add("需代理")
    rule.crawl?.concurrency?.let { add("并发 $it") }
}

@Composable
private fun SourceRow(
    rule: Rule,
    builtIn: Boolean,
    enabled: Boolean,
    status: SourceStatus?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.displayName, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (checked) {
                    Spacer(Modifier.width(8.dp))
                    DelayBadge(status)
                }
            }
            Text(rule.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val tags = sourceTags(rule, builtIn)
            if (tags.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Pill(it) } }
            }
            rule.comment?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
    HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun DelayBadge(status: SourceStatus?) {
    if (status == null) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        return
    }
    val (text, color) = when {
        status.delayMs < 0 -> "超时" to MaterialTheme.colorScheme.error
        status.code >= 400 -> "${status.code}" to Color(0xFFE08A00)
        status.delayMs < 800 -> "${status.delayMs} ms" to Color(0xFF2E9E6A)
        else -> "${status.delayMs} ms" to Color(0xFFE08A00)
    }
    AssistChip(
        onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall, color = color) },
        modifier = Modifier.height(24.dp),
    )
}

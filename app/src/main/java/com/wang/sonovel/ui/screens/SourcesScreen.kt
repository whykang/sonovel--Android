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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.core.Misc
import com.wang.sonovel.data.Rule
import com.wang.sonovel.data.SourceStatus
import com.wang.sonovel.graph
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(vm: SourcesViewModel = viewModel()) {
    val context = LocalContext.current
    val g = context.graph
    val files by g.rules.files.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    var selectedName by rememberSaveable { mutableStateOf(settings.activeRules) }
    val file = files.firstOrNull { it.name == selectedName } ?: files.firstOrNull()
    var detail by remember { mutableStateOf<Rule?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showTemplate by remember { mutableStateOf(false) }
    var confirmDeleteFile by remember { mutableStateOf(false) }
    val snack = rememberSnack()
    val scope = rememberCoroutineScope()

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
                    selectedName = if (name.endsWith(".json")) name.substringAfterLast('/') else "$name.json"
                    "已导入 $n 个书源"
                }.getOrElse { "导入失败：${it.message}" }
            }
            snack(msg)
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("书源", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { file?.let { vm.check(it.rules) } }, enabled = !checking) {
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
                    if (file != null && (!file.builtIn || file.overridesBuiltIn)) {
                        DropdownMenuItem(
                            text = { Text(if (file.builtIn) "恢复内置版本" else "删除此规则文件") },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                            onClick = { menu = false; confirmDeleteFile = true },
                        )
                    }
                }
            }
        }

        // 规则文件选择
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            files.forEach { f ->
                FilterChip(
                    selected = f.name == file?.name,
                    onClick = { selectedName = f.name },
                    label = { Text("${f.name.removeSuffix(".json")} (${f.rules.size})") },
                    leadingIcon = if (f.name == settings.activeRules) {
                        { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }

        if (file != null) {
            val isActive = file.name == settings.activeRules
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(fileDescription(file.name), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (if (file.builtIn) "内置" else "已导入") + (if (file.overridesBuiltIn) "（已被导入文件覆盖）" else "") +
                                " · ${file.rules.size} 个书源",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isActive) {
                        Pill("使用中", container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Button(onClick = { g.rules.setActive(file.name) }) { Text("设为当前") }
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(file?.rules.orEmpty(), key = { it.key }) { r ->
                SourceRow(
                    rule = r,
                    enabled = g.rules.isEnabled(r),
                    toggleable = !r.disabled,
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
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(r.displayName, style = MaterialTheme.typography.titleLarge)
                Text(r.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                r.comment?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

    if (confirmDeleteFile && file != null) {
        ConfirmDialog(
            title = if (file.builtIn) "恢复内置版本？" else "删除 ${file.name}？",
            text = if (file.builtIn) "将删除导入的同名文件，恢复为应用内置的规则。" else "删除后该规则文件中的书源将不可用。",
            onDismiss = { confirmDeleteFile = false },
            onConfirm = {
                g.rules.deleteUserFile(file.name)
                if (!file.builtIn) selectedName = "main.json"
            },
        )
    }
}

private fun fileDescription(name: String) = when (name) {
    "main.json" -> "默认书源，均支持搜索（多数需大陆 IP）"
    "proxy-required.json" -> "需要代理（非大陆 IP），部分需配置 cf-bypass"
    "rate-limit.json" -> "限流严重的书源，建议并发 1~5"
    "no-search.json" -> "不支持搜索，请用“链接下载”粘贴详情页地址"
    "cloudflare.json" -> "有 Cloudflare 保护，需在设置中配置 cf-bypass"
    else -> "自定义规则文件"
}

@Composable
private fun SourceRow(
    rule: Rule,
    enabled: Boolean,
    toggleable: Boolean,
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
                    "${rule.id}. ${rule.displayName}", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (checked) {
                    Spacer(Modifier.width(8.dp))
                    DelayBadge(status)
                }
            }
            Text(rule.url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val tags = buildList {
                if (rule.disabled) add("已禁用")
                if (!rule.searchable) add("不支持搜索")
                if (rule.needProxy) add("需代理")
                rule.crawl?.concurrency?.let { add("并发 $it") }
            }
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
        Switch(checked = enabled, onCheckedChange = onToggle, enabled = toggleable)
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

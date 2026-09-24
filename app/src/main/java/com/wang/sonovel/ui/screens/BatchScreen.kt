package com.wang.sonovel.ui.screens

import android.app.Application
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.core.SearchParser
import com.wang.sonovel.core.SourceContext
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.SearchResult
import com.wang.sonovel.download.DownloadRequest
import com.wang.sonovel.graph
import com.wang.sonovel.ui.LocalSnackbar
import com.wang.sonovel.ui.rememberDownloadPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BatchState(
    val running: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val found: List<SearchResult> = emptyList(),
    val notFound: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
)

class BatchViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val state = MutableStateFlow(BatchState())
    private var job: Job? = null

    fun match(sourceKey: String, input: String) {
        val rule = g.rules.byKey(sourceKey) ?: return
        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "#" }
        job?.cancel()
        state.value = BatchState(running = true, total = lines.size)
        job = viewModelScope.launch(Dispatchers.IO) {
            val ctx = SourceContext(rule, g.settings.current)
            for ((i, line) in lines.withIndex()) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) {
                    state.update { it.copy(notFound = it.notFound + line, progress = i + 1) }
                    continue
                }
                val author = parts.last()
                val name = parts.dropLast(1).joinToString(" ")
                val hit = runCatching { SearchParser(ctx).search(name) }.getOrDefault(emptyList())
                    .firstOrNull { it.bookName == name && it.author == author }
                state.update {
                    if (hit != null) it.copy(found = it.found + hit, selected = it.selected + hit.url, progress = i + 1)
                    else it.copy(notFound = it.notFound + line, progress = i + 1)
                }
                delay(ctx.randomInterval())
            }
            state.update { it.copy(running = false) }
        }
    }

    fun toggle(url: String) = state.update {
        it.copy(selected = if (url in it.selected) it.selected - url else it.selected + url)
    }

    fun downloadSelected(format: ExportFormat): Int {
        val st = state.value
        val list = st.found.filter { it.url in st.selected }
        list.forEach { r ->
            g.downloads.enqueue(
                DownloadRequest(url = r.url, ruleKey = r.sourceKey, format = format),
                bookName = r.bookName, author = r.author.orEmpty(), coverUrl = null,
            )
        }
        return list.size
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(onBack: () -> Unit, onOpenLibrary: () -> Unit, vm: BatchViewModel = viewModel()) {
    val g = LocalContext.current.graph
    val context = LocalContext.current
    val st by vm.state.collectAsStateWithLifecycle()
    val sources = remember { g.rules.searchableRules() }
    var sourceKey by rememberSaveable { mutableStateOf(sources.firstOrNull()?.key) }
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(g.settings.current.format) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val requestPermissions = rememberDownloadPermissions()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("批量下载") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "在指定书源中按“书名 + 作者”精确匹配，每行一本，书名与作者之间用空格分隔。未找到的书可复制后切换书源再试。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("书源：", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { menu = true }) {
                            Text(sources.firstOrNull { it.key == sourceKey }?.displayName ?: "无可用书源", maxLines = 1)
                            Icon(Icons.Outlined.ExpandMore, null)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            sources.forEach { r ->
                                DropdownMenuItem(text = { Text(r.displayName) }, onClick = { sourceKey = r.key; menu = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("书名 作者") },
                        placeholder = { Text("例如：\n诡秘之主 爱潜水的乌贼\n凡人修仙传 忘语") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    )
                    Button(
                        onClick = { sourceKey?.let { vm.match(it, input) } },
                        enabled = !st.running && sourceKey != null && input.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (st.running) "正在匹配 ${st.progress}/${st.total}…" else "开始匹配") }
                    if (st.running) LinearProgressIndicator(
                        progress = { if (st.total == 0) 0f else st.progress.toFloat() / st.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (st.found.isNotEmpty()) {
                item {
                    Text(
                        "已找到 ${st.found.size} 本", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(st.found, key = { it.url }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.toggle(r.url) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = r.url in st.selected, onCheckedChange = { vm.toggle(r.url) })
                        Column(Modifier.weight(1f)) {
                            Text("《${r.bookName}》${r.author.orEmpty()}", style = MaterialTheme.typography.bodyLarge)
                            r.latestChapter?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            ExportFormat.entries.forEachIndexed { i, f ->
                                SegmentedButton(
                                    selected = format == f, onClick = { format = f },
                                    shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size),
                                ) { Text(f.label) }
                            }
                        }
                        Button(
                            onClick = {
                                requestPermissions {
                                    val n = vm.downloadSelected(format)
                                    scope.launch {
                                        val r = snackbar.showSnackbar("已将 $n 本书加入下载队列", actionLabel = "查看")
                                        if (r == SnackbarResult.ActionPerformed) onOpenLibrary()
                                    }
                                }
                            },
                            enabled = st.selected.isNotEmpty() && !st.running,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("下载选中（${st.selected.size}）") }
                    }
                }
            }

            if (st.notFound.isNotEmpty()) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "未找到 ${st.notFound.size} 本", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = {
                                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                    cm.setPrimaryClip(ClipData.newPlainText("books", st.notFound.joinToString("\n")))
                                    input = st.notFound.joinToString("\n")
                                    scope.launch { snackbar.showSnackbar("已复制并填入输入框，可切换书源后重新匹配") }
                                }) { Icon(Icons.Outlined.ContentCopy, "复制") }
                            }
                            st.notFound.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(24.dp).width(1.dp)) }
        }
    }
}

package com.wang.sonovel.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.core.Misc
import com.wang.sonovel.core.SearchParser
import com.wang.sonovel.core.SearchRanker
import com.wang.sonovel.core.SourceContext
import com.wang.sonovel.data.Rule
import com.wang.sonovel.data.SearchResult
import com.wang.sonovel.graph
import com.wang.sonovel.ui.components.BookCover
import com.wang.sonovel.ui.components.EmptyState
import com.wang.sonovel.ui.components.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface SourceSearchState {
    data object Pending : SourceSearchState
    data class Done(val count: Int) : SourceSearchState
    data class Failed(val message: String) : SourceSearchState
}

data class SearchUiState(
    val sourceKey: String? = null,
    val searching: Boolean = false,
    val keyword: String? = null,
    val results: List<SearchResult> = emptyList(),
    val sources: List<Pair<Rule, SourceSearchState>> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val message: String? = null,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    val state = MutableStateFlow(SearchUiState())
    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun selectSource(key: String?) {
        state.update { it.copy(sourceKey = key) }
        state.value.keyword?.let { search(it) }
    }

    fun onQueryChange(q: String) {
        suggestJob?.cancel()
        if (q.isBlank()) {
            state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(350)
            val list = Misc.suggestions(q, g.settings.current)
            state.update { it.copy(suggestions = list.filter { s -> s != q }) }
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        state.update { it.copy(suggestions = emptyList()) }
    }

    fun cancel() {
        searchJob?.cancel()
        state.update { it.copy(searching = false) }
    }

    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        clearSuggestions()
        searchJob?.cancel()
        g.history.add(kw)
        val s = g.settings.current
        val key = state.value.sourceKey
        val sources: List<Rule> = if (key != null) listOfNotNull(g.rules.byKey(key)) else g.rules.searchableRules()
        if (sources.isEmpty()) {
            state.update { it.copy(keyword = kw, results = emptyList(), sources = emptyList(), message = "当前规则文件中没有可搜索的书源，请在“书源”页切换规则文件") }
            return
        }
        state.update {
            it.copy(
                keyword = kw, searching = true, results = emptyList(), message = null,
                sources = sources.map { r -> r to SourceSearchState.Pending },
            )
        }
        val collected = ArrayList<SearchResult>()
        searchJob = viewModelScope.launch {
            sources.map { rule ->
                launch(Dispatchers.IO) {
                    val result = runCatching {
                        withTimeout(45_000) { SearchParser(SourceContext(rule, s)).search(kw) }
                    }
                    synchronized(collected) {
                        result.getOrNull()?.let { collected += it }
                        val ranked = if (key == null) SearchRanker.filterAndSort(collected.toList(), kw, s.searchFilter)
                        else collected.toList()
                        state.update { st ->
                            st.copy(
                                results = ranked,
                                sources = st.sources.map { (r, old) ->
                                    if (r.key != rule.key) r to old
                                    else r to result.fold(
                                        { SourceSearchState.Done(it.size) },
                                        { e -> SourceSearchState.Failed(e.message?.take(120) ?: e.javaClass.simpleName) },
                                    )
                                },
                            )
                        }
                    }
                }
            }.joinAll()
            state.update { it.copy(searching = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenBook: (SearchResult) -> Unit,
    onBatch: () -> Unit,
    onLink: () -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val g = androidx.compose.ui.platform.LocalContext.current.graph
    val history by g.history.items.collectAsStateWithLifecycle()
    val ruleFiles by g.rules.files.collectAsStateWithLifecycle()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(ui.keyword.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    val activeFile = ruleFiles.firstOrNull { it.name == settings.activeRules } ?: ruleFiles.firstOrNull()
    val searchable = remember(activeFile, settings.disabledSources) { g.rules.searchableRules() }
    val selectedRule = ui.sourceKey?.let { g.rules.byKey(it) }

    fun doSearch(q: String) {
        query = q
        focus.clearFocus()
        vm.search(q)
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        // 标题栏
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("So Novel", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${activeFile?.name ?: "-"} · ${searchable.size} 个可搜索书源",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLink) { Icon(Icons.Outlined.Link, "链接下载") }
            IconButton(onClick = onBatch) { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, "批量下载") }
        }

        // 搜索框
        TextField(
            value = query,
            onValueChange = { query = it; vm.onQueryChange(it) },
            placeholder = { Text("输入书名或作者（尽量完整）") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.clearSuggestions() }) {
                    Icon(Icons.Outlined.Close, "清空")
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .onFocusChanged { focused = it.isFocused },
        )

        // 书源选择 + 状态
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = selectedRule != null,
                onClick = { showSourcePicker = true },
                label = { Text(selectedRule?.let { "书源：${it.displayName}" } ?: "聚合搜索（全部书源）", maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)) },
            )
            Spacer(Modifier.weight(1f))
            if (ui.sources.isNotEmpty()) {
                val done = ui.sources.count { it.second !is SourceSearchState.Pending }
                TextButton(onClick = { showStatus = true }) {
                    Text(if (ui.searching) "搜索中 $done/${ui.sources.size}" else "共 ${ui.results.size} 条结果")
                }
            }
        }
        AnimatedVisibility(ui.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50)))
        }

        val showSuggestions = focused && ui.suggestions.isNotEmpty() && query.isNotBlank()
        when {
            showSuggestions -> LazyColumn(Modifier.fillMaxSize()) {
                items(ui.suggestions) { s ->
                    ListItem(
                        headlineContent = { Text(s) },
                        leadingContent = { Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable { doSearch(s) },
                    )
                }
            }

            ui.keyword == null -> LazyColumn(Modifier.fillMaxSize()) {
                if (history.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("搜索历史", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { g.history.clear() }) { Icon(Icons.Outlined.DeleteSweep, "清空历史") }
                    }
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { h ->
                            InputChip(
                                selected = false,
                                onClick = { doSearch(h) },
                                label = { Text(h, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, "删除", Modifier.size(16.dp).clickable { g.history.remove(h) })
                                },
                            )
                        }
                    }
                }
                item { TipsCard(onLink, onBatch) }
            }

            ui.message != null -> EmptyState(Icons.Outlined.SearchOff, ui.message!!)

            ui.results.isEmpty() && !ui.searching -> EmptyState(
                Icons.Outlined.SearchOff, "没有找到“${ui.keyword}”",
                "试试输入完整书名，或在“书源”页切换其他规则文件（部分书源需要代理）",
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(ui.results, key = { it.sourceKey + it.url }) { r ->
                    ResultItem(r, showSource = ui.sourceKey == null) { onOpenBook(r) }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showSourcePicker) {
        ModalBottomSheet(onDismissRequest = { showSourcePicker = false }) {
            Text("选择书源", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "聚合搜索会同时查询所有书源并按相似度排序",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("聚合搜索（全部书源）") },
                        trailingContent = { if (ui.sourceKey == null) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { vm.selectSource(null); showSourcePicker = false },
                    )
                }
                items(searchable, key = { it.key }) { r ->
                    ListItem(
                        headlineContent = { Text(r.displayName) },
                        supportingContent = { Text(r.url.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { if (ui.sourceKey == r.key) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { vm.selectSource(r.key); showSourcePicker = false },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showStatus) {
        ModalBottomSheet(onDismissRequest = { showStatus = false }) {
            Text("书源搜索情况", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            LazyColumn {
                items(ui.sources, key = { it.first.key }) { (rule, st) ->
                    ListItem(
                        headlineContent = { Text(rule.displayName) },
                        supportingContent = {
                            when (st) {
                                SourceSearchState.Pending -> Text("搜索中…")
                                is SourceSearchState.Done -> Text("找到 ${st.count} 条")
                                is SourceSearchState.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error, maxLines = 2)
                            }
                        },
                        trailingContent = {
                            when (st) {
                                SourceSearchState.Pending -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                is SourceSearchState.Done -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                is SourceSearchState.Failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ResultItem(r: SearchResult, showSource: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BookCover(null, r.bookName, width = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.bookName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(r.author, r.category, r.status, r.wordCount).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            r.latestChapter?.let {
                Text(
                    "最新：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showSource) Pill(r.sourceName)
                r.lastUpdateTime?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it.replace(Regex("\\d{2}:\\d{2}(:\\d{2})?"), "").trim(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 78.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun TipsCard(onLink: () -> Unit, onBatch: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("使用提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("• 聚合搜索会同时查询当前规则文件中的全部书源", style = MaterialTheme.typography.bodyMedium)
            Text("• 找到书后可选择下载全本、指定范围或最新章节", style = MaterialTheme.typography.bodyMedium)
            Text("• 支持导出 EPUB / TXT / HTML / PDF，默认格式可在设置中修改", style = MaterialTheme.typography.bodyMedium)
            Text("• 不支持搜索的书源可通过“链接下载”粘贴详情页地址", style = MaterialTheme.typography.bodyMedium)
            Text("• 下载完成后在“书架”点击书籍即可直接阅读，支持左右翻页", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLink) { Text("链接下载") }
                TextButton(onClick = onBatch) { Text("批量下载") }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "本项目参考开源项目 So Novel（github.com/freeok/so-novel）开发，书源规则与功能设计均来自原项目。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "本应用仅供交流学习使用，所有内容均来自第三方网站，请勿用于商业用途，请支持正版。如有侵权，请联系删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

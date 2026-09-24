package com.wang.sonovel.ui.screens

import android.app.Application
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.core.BookParser
import com.wang.sonovel.core.ChapterParser
import com.wang.sonovel.core.ChapterProcessor
import com.wang.sonovel.core.CoverFetcher
import com.wang.sonovel.core.SourceContext
import com.wang.sonovel.core.TocParser
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.ChapterContent
import com.wang.sonovel.data.ChapterRef
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.LangType
import com.wang.sonovel.data.Rule
import com.wang.sonovel.download.DownloadRequest
import com.wang.sonovel.graph
import com.wang.sonovel.ui.LocalSnackbar
import com.wang.sonovel.ui.components.BookCover
import com.wang.sonovel.ui.components.Pill
import com.wang.sonovel.ui.rememberDownloadPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookDetailState(
    val rule: Rule? = null,
    val book: BookInfo? = null,
    val bookLoading: Boolean = true,
    val bookError: String? = null,
    val betterCover: String? = null,
    val toc: List<ChapterRef>? = null,
    val tocLoading: Boolean = true,
    val tocError: String? = null,
)

sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val content: ChapterContent) : PreviewState
    data class Failed(val message: String) : PreviewState
}

class BookDetailViewModel(app: Application, private val ruleKey: String?, val url: String) : AndroidViewModel(app) {
    private val g = app.graph
    val state = MutableStateFlow(BookDetailState())
    val preview = MutableStateFlow<Pair<Int, PreviewState>?>(null)

    init {
        load()
    }

    private fun ctx(): SourceContext? = state.value.rule?.let { SourceContext(it, g.settings.current) }

    fun load() {
        val rule = ruleKey?.let { g.rules.byKey(it) } ?: g.rules.matchByUrl(url)
        if (rule == null) {
            state.value = BookDetailState(bookLoading = false, tocLoading = false, bookError = "找不到与该链接匹配的书源", tocError = "")
            return
        }
        state.value = BookDetailState(rule = rule)
        val ctx = SourceContext(rule, g.settings.current)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { BookParser(ctx).parse(url) }
                .onSuccess { b ->
                    state.update { it.copy(book = b, bookLoading = false) }
                    if (ctx.settings.fetchBetterCover && !rule.needProxy) {
                        runCatching { CoverFetcher.best(b, ctx.settings) }.getOrNull()?.let { c ->
                            if (c != b.coverUrl) state.update { it.copy(betterCover = c) }
                        }
                    }
                }
                .onFailure { e -> state.update { it.copy(bookLoading = false, bookError = e.message ?: "解析失败") } }
        }
        loadToc(ctx)
    }

    private fun loadToc(ctx: SourceContext) {
        state.update { it.copy(tocLoading = true, tocError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TocParser(ctx).parseAll(url) }
                .onSuccess { t -> state.update { it.copy(toc = t, tocLoading = false, tocError = if (t.isEmpty()) "目录为空（可能有反爬限制）" else null) } }
                .onFailure { e -> state.update { it.copy(tocLoading = false, tocError = e.message ?: "目录解析失败") } }
        }
    }

    fun retryToc() {
        ctx()?.let { loadToc(it) }
    }

    fun openPreview(index: Int) {
        val toc = state.value.toc ?: return
        val c = toc.getOrNull(index) ?: return
        val ctx = ctx() ?: return
        preview.value = index to PreviewState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = ChapterParser(ctx).fetchContent(c.url, 0)
                    ChapterProcessor(ctx).process(c.order, c.title, raw)
                }
            }
            if (preview.value?.first == index) {
                preview.value = index to result.fold({ PreviewState.Loaded(it) }, { PreviewState.Failed(it.message ?: "加载失败") })
            }
        }
    }

    fun closePreview() {
        preview.value = null
    }

    fun enqueue(format: ExportFormat, chapters: List<ChapterRef>?, rangeLabel: String, language: String?, concurrency: Int?) {
        val st = state.value
        val book = st.book
        g.downloads.enqueue(
            DownloadRequest(
                url = url, ruleKey = st.rule?.key, book = book, chapters = chapters, rangeLabel = rangeLabel,
                format = format, language = language, concurrency = concurrency,
            ),
            bookName = book?.bookName.orEmpty(), author = book?.author.orEmpty(), coverUrl = st.betterCover ?: book?.coverUrl,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    ruleKey: String?,
    url: String,
    initialName: String,
    initialAuthor: String,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: BookDetailViewModel = viewModel(key = url) { BookDetailViewModel(app, ruleKey, url) }
    val st by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var showDownload by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(false) }
    var introExpanded by remember { mutableStateOf(false) }
    val requestPermissions = rememberDownloadPermissions()
    val listState = rememberLazyListState()

    val name = st.book?.bookName ?: initialName
    val author = st.book?.author ?: initialAuthor

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (listState.firstVisibleItemIndex > 0) name else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val toc = st.toc.orEmpty().let { if (descending) it.asReversed() else it }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            // 头部信息
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    BookCover(st.betterCover ?: st.book?.coverUrl, name.ifBlank { "书" }, width = 108.dp)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "加载中…" }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (author.isNotBlank()) {
                            Text(author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            st.book?.category?.let { Pill(it) }
                            st.book?.status?.let { Pill(it, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer) }
                            st.rule?.let { Pill(it.displayName, container = MaterialTheme.colorScheme.surfaceContainerHighest, content = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Spacer(Modifier.height(8.dp))
                        st.book?.latestChapter?.let {
                            Text("最新：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        st.book?.lastUpdateTime?.let {
                            Text("更新：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 操作按钮
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showDownload = true },
                        enabled = st.rule != null && st.bookError == null,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("下载")
                    }
                    FilledTonalButton(
                        onClick = { vm.openPreview(0) },
                        enabled = !st.toc.isNullOrEmpty(),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("试读")
                    }
                }
            }

            if (st.bookLoading) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            st.bookError?.let { err ->
                item { ErrorCard(err, onRetry = { vm.load() }) }
            }

            // 简介
            st.book?.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                item {
                    Card(
                        onClick = { introExpanded = !introExpanded },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("简介", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                intro, style = MaterialTheme.typography.bodyMedium,
                                maxLines = if (introExpanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 目录
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (st.toc != null) "目录 · 共 ${st.toc!!.size} 章" else "目录",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    )
                    if (!st.toc.isNullOrEmpty()) {
                        TextButton(onClick = { descending = !descending }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (descending) "倒序" else "正序")
                        }
                    }
                }
            }
            if (st.tocLoading) item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在解析目录…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            st.tocError?.takeIf { it.isNotBlank() }?.let { err -> item { ErrorCard(err, onRetry = { vm.retryToc() }) } }
            items(toc, key = { it.order }) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { vm.openPreview(st.toc!!.indexOf(c)) }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${c.order}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }

    if (showDownload) {
        DownloadSheet(
            toc = st.toc,
            onDismiss = { showDownload = false },
            onStart = { format, chapters, label, lang, conc ->
                showDownload = false
                requestPermissions {
                    vm.enqueue(format, chapters, label, lang, conc)
                    scope.launch {
                        val r = snackbar.showSnackbar("《$name》已加入下载队列", actionLabel = "查看")
                        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenLibrary()
                    }
                }
            },
        )
    }

    preview?.let { (index, ps) ->
        ChapterPreviewDialog(
            title = st.toc?.getOrNull(index)?.title.orEmpty(),
            state = ps,
            hasPrev = index > 0,
            hasNext = index < (st.toc?.size ?: 0) - 1,
            onPrev = { vm.openPreview(index - 1) },
            onNext = { vm.openPreview(index + 1) },
            onRetry = { vm.openPreview(index) },
            onDismiss = { vm.closePreview() },
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private enum class RangeMode(val label: String) { ALL("全本"), RANGE("指定范围"), LATEST("最新章节") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    toc: List<ChapterRef>?,
    onDismiss: () -> Unit,
    onStart: (ExportFormat, List<ChapterRef>?, String, String?, Int?) -> Unit,
) {
    val g = LocalContext.current.graph
    val settings = g.settings.current
    var format by remember { mutableStateOf(settings.format) }
    var mode by remember { mutableStateOf(RangeMode.ALL) }
    val total = toc?.size ?: 0
    var start by remember { mutableStateOf("1") }
    var end by remember { mutableStateOf(total.toString()) }
    var latest by remember { mutableStateOf("50") }
    var language by remember { mutableStateOf<String?>(null) }
    var concurrency by remember { mutableIntStateOf(0) }

    val s = start.toIntOrNull()
    val e = end.toIntOrNull()
    val l = latest.toIntOrNull()
    val rangeError = when (mode) {
        RangeMode.ALL -> null
        RangeMode.RANGE -> if (s == null || e == null || s < 1 || e > total || s > e) "请输入 1 ~ $total 之间的有效范围" else null
        RangeMode.LATEST -> if (l == null || l < 1) "请输入有效数量" else null
    }
    val count = when (mode) {
        RangeMode.ALL -> total
        RangeMode.RANGE -> if (rangeError == null) e!! - s!! + 1 else 0
        RangeMode.LATEST -> minOf(l ?: 0, total)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text("下载选项", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("下载范围", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RangeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        enabled = m == RangeMode.ALL || total > 0,
                        shape = SegmentedButtonDefaults.itemShape(i, RangeMode.entries.size),
                    ) { Text(m.label) }
                }
            }
            when (mode) {
                RangeMode.RANGE -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        start, { start = it.filter(Char::isDigit) }, label = { Text("起始章") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    Text("  —  ")
                    OutlinedTextField(
                        end, { end = it.filter(Char::isDigit) }, label = { Text("结束章") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                RangeMode.LATEST -> OutlinedTextField(
                    latest, { latest = it.filter(Char::isDigit) }, label = { Text("最新章节数量") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                RangeMode.ALL -> Unit
            }
            Text(
                rangeError ?: if (toc == null) "目录尚未解析完成，将在下载时解析" else "将下载 $count / $total 章",
                style = MaterialTheme.typography.bodySmall,
                color = if (rangeError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            Text("导出格式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = format == f, onClick = { format = f },
                        shape = SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size),
                    ) { Text(f.label) }
                }
            }
            Text(
                when (format) {
                    ExportFormat.EPUB -> "推荐：带封面与目录，适合各类电子书阅读器"
                    ExportFormat.TXT -> "纯文本，编码 ${settings.txtEncoding}（可在设置中改为 GBK）"
                    ExportFormat.HTML -> "网页格式，打包为 zip，包含目录页与翻页"
                    ExportFormat.PDF -> "适合打印或固定排版阅读，大部头书籍生成较慢"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            Text("内容语言", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, LangType.ZH_CN, LangType.ZH_HANT, LangType.ZH_TW).forEach { lang ->
                    FilterChip(
                        selected = language == lang, onClick = { language = lang },
                        label = { Text(if (lang == null) "跟随设置" else LangType.label(lang)) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("并发数", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 3, 5, 10, 20, 50).forEach { c ->
                    FilterChip(
                        selected = concurrency == c, onClick = { concurrency = c },
                        label = { Text(if (c == 0) "默认" else "$c") },
                    )
                }
            }
            Text(
                "默认使用设置及书源推荐值；遇到限流时请调低并发",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val (chapters, label) = when (mode) {
                        RangeMode.ALL -> toc to "全本"
                        RangeMode.RANGE -> toc!!.subList(s!! - 1, e!!) to "第 $s ~ $e 章"
                        RangeMode.LATEST -> toc!!.takeLast(minOf(l!!, total)) to "最新 ${minOf(l, total)} 章"
                    }
                    onStart(format, chapters, label, language, concurrency.takeIf { it > 0 })
                },
                enabled = rangeError == null,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("开始下载") }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun ChapterPreviewDialog(
    title: String,
    state: PreviewState,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxSize(0.9f),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (state) {
                        PreviewState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is PreviewState.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("重试") }
                        }
                        is PreviewState.Loaded -> {
                            val paragraphs = remember(state) { ChapterProcessor.paragraphs(state.content.html) }
                            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                                item {
                                    Text(state.content.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
                                }
                                items(paragraphs) { p ->
                                    Text(
                                        "　　$p",
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.8),
                                        modifier = Modifier.padding(vertical = 6.dp),
                                    )
                                }
                                item { Spacer(Modifier.heightIn(min = 24.dp)) }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onPrev, enabled = hasPrev, modifier = Modifier.weight(1f)) { Text("上一章") }
                    OutlinedButton(onClick = onNext, enabled = hasNext, modifier = Modifier.weight(1f)) { Text("下一章") }
                }
            }
        }
    }
}

/** 链接下载：粘贴书籍详情页地址 */
@Composable
fun LinkDialog(initial: String, onDismiss: () -> Unit, onOpen: (String, String) -> Unit) {
    val g = LocalContext.current.graph
    var text by remember { mutableStateOf(initial) }
    val url = text.trim()
    val rule = remember(url) { if (url.startsWith("http")) g.rules.matchByUrl(url) else null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("链接下载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴书籍详情页地址（适用于不支持搜索的书源），支持全部规则文件中的书源",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (url.isNotEmpty()) {
                    Text(
                        rule?.let { "匹配书源：${it.displayName}（${it.file}）" } ?: "未找到匹配的书源",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rule != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { rule?.let { onOpen(it.key, url) } }, enabled = rule != null) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

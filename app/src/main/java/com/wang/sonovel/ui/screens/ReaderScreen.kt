package com.wang.sonovel.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wang.sonovel.graph
import com.wang.sonovel.reader.BookReadSource
import com.wang.sonovel.reader.PageMode
import com.wang.sonovel.reader.ReaderChapter
import com.wang.sonovel.reader.ReaderPrefs
import com.wang.sonovel.reader.ReaderTheme
import com.wang.sonovel.reader.ReadingPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val chapters: List<ReaderChapter> = emptyList(),
    val index: Int = 0,
    val paragraphs: List<String> = emptyList(),
    val chapterLoading: Boolean = false,
    /** 打开章节后要定位到的段落：列表项序号（0 为标题，i+1 为第 i 段）与像素偏移 */
    val restore: Pair<Int, Int>? = null,
    /** 从下一章往回翻时，定位到本章最后一页 */
    val startAtEnd: Boolean = false,
    /** 每加载一次章节自增，用于触发定位 */
    val version: Int = 0,
)

class ReaderViewModel(app: Application, private val path: String) : AndroidViewModel(app) {
    private val g = app.graph
    val file = File(path)
    val state = MutableStateFlow(ReaderState())
    private var source: BookReadSource? = null
    private var loadJob: Job? = null

    /** 当前阅读位置（列表项序号, 偏移），由界面实时更新 */
    var position: Pair<Int, Int> = 0 to 0

    init {
        load()
    }

    fun load() {
        state.value = ReaderState(loading = true, title = file.nameWithoutExtension)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { BookReadSource.open(file) } }
            result.onSuccess { src ->
                source?.close()
                source = src
                val saved = g.progress.get(file)
                val start = saved?.chapter?.coerceIn(0, src.chapters.size - 1) ?: 0
                state.update { it.copy(loading = false, chapters = src.chapters, index = start) }
                openChapter(start, restore = saved?.let { s -> s.item to s.offset })
            }.onFailure { e ->
                state.update { it.copy(loading = false, error = e.message ?: "无法打开该文件") }
            }
        }
    }

    fun openChapter(index: Int, restore: Pair<Int, Int>? = null, toEnd: Boolean = false) {
        val src = source ?: return
        if (index !in src.chapters.indices) return
        loadJob?.cancel()
        state.update { it.copy(chapterLoading = true) }
        loadJob = viewModelScope.launch {
            val paras = withContext(Dispatchers.IO) {
                runCatching { src.paragraphs(index) }.getOrElse { listOf("本章内容加载失败：${it.message}") }
            }
            position = restore ?: (0 to 0)
            // 章节序号与正文同时更新，避免翻页时闪烁
            state.update {
                it.copy(
                    index = index, paragraphs = paras, chapterLoading = false,
                    restore = restore, startAtEnd = toEnd, version = it.version + 1,
                )
            }
            saveProgress()
        }
    }

    fun next() = openChapter(state.value.index + 1)
    fun prev(toEnd: Boolean = false) = openChapter(state.value.index - 1, toEnd = toEnd)

    fun saveProgress() {
        val s = state.value
        if (s.chapters.isEmpty()) return
        g.progress.save(file, ReadingPosition(s.index, position.first, position.second, s.chapters.size))
    }

    override fun onCleared() {
        saveProgress()
        source?.close()
        source = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val prefs by context.graph.readerPrefs.state.collectAsStateWithLifecycle()
    if (file.extension.equals("pdf", true)) {
        PdfReaderScreen(file, slide = prefs.pageMode == PageMode.SLIDE, onBack = onBack)
        return
    }

    val app = context.applicationContext as Application
    val vm: ReaderViewModel = viewModel(key = path) { ReaderViewModel(app, path) }
    val st by vm.state.collectAsStateWithLifecycle()
    var chromeVisible by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val view = LocalView.current

    val custom = prefs.theme.colors
    val bg = custom?.first ?: MaterialTheme.colorScheme.surface
    val fg = custom?.second ?: MaterialTheme.colorScheme.onSurface

    // 阅读时保持屏幕常亮
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // 离开阅读器时保存进度
    DisposableEffect(Unit) { onDispose { vm.saveProgress() } }

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            st.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            st.error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(st.error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBack) { Text("返回") }
                    Button(onClick = { vm.load() }) { Text("重试") }
                }
            }
            st.paragraphs.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = fg.copy(alpha = 0.6f))
            prefs.pageMode == PageMode.SLIDE -> PagedReader(
                st = st, vm = vm, prefs = prefs, fg = fg,
                chromeVisible = chromeVisible,
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
            else -> ScrollReader(
                st = st, vm = vm, prefs = prefs, fg = fg,
                chromeVisible = chromeVisible,
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
        }

        // 顶栏
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(st.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        st.chapters.getOrNull(st.index)?.let {
                            Text(
                                it.title, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 底栏
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp)) {
                    if (st.chapters.size > 1) {
                        // 拖动时只预览章节名，松手后再跳转
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        val shown = (dragging ?: st.index.toFloat()).toInt()
                        Text(
                            st.chapters.getOrNull(shown)?.title.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButtonSmall("上一章", enabled = st.index > 0) { vm.prev() }
                            Slider(
                                value = dragging ?: st.index.toFloat(),
                                onValueChange = { dragging = it },
                                onValueChangeFinished = {
                                    dragging?.let { vm.openChapter(it.toInt()) }
                                    dragging = null
                                },
                                valueRange = 0f..(st.chapters.size - 1).toFloat(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            OutlinedButtonSmall("下一章", enabled = st.index < st.chapters.size - 1) { vm.next() }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BottomAction(Icons.AutoMirrored.Outlined.List, "目录") { showToc = true }
                        BottomAction(Icons.Outlined.TextFields, "字号") { showSettings = true }
                        BottomAction(Icons.Outlined.Palette, "设置") { showSettings = true }
                    }
                }
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(
            onDismissRequest = { showToc = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val tocState = rememberLazyListState()
            LaunchedEffect(Unit) { tocState.scrollToItem((st.index - 3).coerceAtLeast(0)) }
            Text(
                "目录 · 共 ${st.chapters.size} 章",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(state = tocState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(st.chapters) { i, c ->
                    val selected = i == st.index
                    Text(
                        c.title,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showToc = false
                                chromeVisible = false
                                vm.openChapter(i)
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(prefs, onDismiss = { showSettings = false })
    }
}

// ================================ 左右翻页 ================================

/** 一页的范围：TextLayoutResult 中的纵向区间 */
private data class PageRange(val top: Float, val bottom: Float, val firstLine: Int)

private class ChapterPages(
    val layout: TextLayoutResult,
    val pages: List<PageRange>,
    /** 每个列表项（0 标题，i+1 第 i 段）在文本中的起始字符位置 */
    val itemStarts: IntArray,
) {
    /** 页面起始处所在的列表项 */
    fun itemOfPage(page: Int): Int {
        val p = pages.getOrNull(page) ?: return 0
        val offset = layout.getLineStart(p.firstLine)
        var idx = itemStarts.binarySearch(offset)
        if (idx < 0) idx = -idx - 2
        return idx.coerceIn(0, itemStarts.size - 1)
    }

    /** 包含指定列表项开头的页 */
    fun pageOfItem(item: Int): Int {
        val offset = itemStarts.getOrElse(item.coerceIn(0, itemStarts.size - 1)) { 0 }
        val line = layout.getLineForOffset(offset)
        return pages.indexOfLast { it.firstLine <= line }.coerceAtLeast(0)
    }
}

private fun paginate(
    measurer: TextMeasurer,
    title: String,
    paragraphs: List<String>,
    prefs: ReaderPrefs,
    widthPx: Int,
    heightPx: Float,
): ChapterPages {
    val fs = prefs.fontSize
    val starts = IntArray(paragraphs.size + 1)
    val text = buildAnnotatedString(title, paragraphs, prefs, starts)
    val layout = measurer.measure(
        text = text,
        style = TextStyle(fontSize = fs.sp, lineHeight = (fs * prefs.lineHeight).sp),
        constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)),
    )
    val pages = mutableListOf<PageRange>()
    var start = 0
    while (start < layout.lineCount) {
        val top = layout.getLineTop(start)
        var end = start
        // 按整行分页，保证不会截断文字
        while (end + 1 < layout.lineCount && layout.getLineBottom(end + 1) - top <= heightPx) end++
        pages += PageRange(top, layout.getLineBottom(end), start)
        start = end + 1
    }
    if (pages.isEmpty()) pages += PageRange(0f, 0f, 0)
    return ChapterPages(layout, pages, starts)
}

private fun buildAnnotatedString(
    title: String,
    paragraphs: List<String>,
    prefs: ReaderPrefs,
    starts: IntArray,
): AnnotatedString {
    val fs = prefs.fontSize
    val body = ParagraphStyle(lineHeight = (fs * prefs.lineHeight).sp)
    // 段间距：一个高度较小的空行
    val gap = ParagraphStyle(lineHeight = (fs * 0.55f).sp)
    val b = AnnotatedString.Builder()
    starts[0] = 0
    b.withStyle(ParagraphStyle(lineHeight = (fs * 2.1f).sp)) {
        withStyle(SpanStyle(fontSize = (fs + 5).sp, fontWeight = FontWeight.Bold)) { append(title) }
    }
    b.withStyle(ParagraphStyle(lineHeight = (fs * 0.8f).sp)) { withStyle(SpanStyle(fontSize = 4.sp)) { append("\u200B") } }
    paragraphs.forEachIndexed { i, p ->
        starts[i + 1] = b.length
        b.withStyle(body) {
            append("\u3000\u3000")
            append(p)
        }
        if (i < paragraphs.size - 1) {
            b.withStyle(gap) { withStyle(SpanStyle(fontSize = 4.sp)) { append("\u200B") } }
        }
    }
    return b.toAnnotatedString()
}

@Composable
private fun PagedReader(
    st: ReaderState,
    vm: ReaderViewModel,
    prefs: ReaderPrefs,
    fg: Color,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val scope = rememberCoroutineScope()
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPad = with(density) { 24.dp.roundToPx() }
        val headerPx = with(density) { 36.dp.roundToPx() }
        val footerPx = with(density) { 32.dp.roundToPx() }
        val widthPx = constraints.maxWidth - hPad * 2
        val heightPx = (constraints.maxHeight - statusTop - navBottom - headerPx - footerPx).toFloat()
        val chapterTitle = st.chapters.getOrNull(st.index)?.title.orEmpty()

        val pages = remember(st.version, prefs.fontSize, prefs.lineHeight, widthPx, heightPx) {
            paginate(measurer, chapterTitle, st.paragraphs, prefs, widthPx, heightPx)
        }

        // 每个章节使用独立的 PagerState；两端各留一个占位页用于跨章翻页
        key(st.version) {
            val pagerState = rememberPagerState(initialPage = 1) { pages.pages.size + 2 }
            var positioned by remember { mutableStateOf(false) }
            var firstLayout by remember { mutableStateOf(true) }

            // 打开章节时定位（往回翻进入上一章则到末页）；调整字号后停留在同一段落
            LaunchedEffect(pages) {
                positioned = false
                val target = if (firstLayout && st.startAtEnd) pages.pages.size
                else pages.pageOfItem(vm.position.first) + 1
                firstLayout = false
                pagerState.scrollToPage(target.coerceIn(1, pages.pages.size))
                positioned = true
            }

            // 翻到占位页时切换章节，否则记录进度
            LaunchedEffect(pagerState, pages) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    if (!positioned) return@collect
                    val last = pages.pages.size + 1
                    when (page) {
                        0 -> if (st.index > 0) vm.prev(toEnd = true) else {
                            pagerState.scrollToPage(1)
                            Toast.makeText(context, "已经是第一章了", Toast.LENGTH_SHORT).show()
                        }
                        last -> if (st.index < st.chapters.size - 1) vm.next() else {
                            pagerState.scrollToPage(pages.pages.size)
                            Toast.makeText(context, "已经是最后一章了", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            vm.position = pages.itemOfPage(page - 1) to 0
                            vm.saveProgress()
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
                key = { it },
            ) { page ->
                val range = pages.pages.getOrNull(page - 1)
                Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(chromeVisible) {
                            detectTapGestures { off ->
                                val w = size.width
                                when {
                                    chromeVisible -> onToggleChrome()
                                    off.x < w / 3f -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                    off.x > w * 2f / 3f -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                    else -> onToggleChrome()
                                }
                            }
                        },
                ) {
                    Spacer(Modifier.height(with(density) { statusTop.toDp() }))
                    // 页眉：章节名
                    Box(
                        Modifier.fillMaxWidth().height(with(density) { headerPx.toDp() })
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            if (range != null && page > 1) chapterTitle else st.title,
                            color = fg.copy(alpha = 0.5f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 正文
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
                        if (range != null) {
                            Canvas(Modifier.fillMaxSize()) {
                                clipRect(bottom = range.bottom - range.top) {
                                    drawText(pages.layout, color = fg, topLeft = Offset(0f, -range.top))
                                }
                            }
                        } else {
                            // 占位页：提示切换章节
                            val prev = page == 0
                            val target = st.chapters.getOrNull(if (prev) st.index - 1 else st.index + 1)
                            Text(
                                target?.let { (if (prev) "上一章\n" else "下一章\n") + it.title }
                                    ?: if (prev) "已经是第一章了" else "全书完",
                                color = fg.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                    // 页脚：章节进度 + 页码
                    Row(
                        Modifier.fillMaxWidth().height(with(density) { footerPx.toDp() }).padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${st.index + 1} / ${st.chapters.size} 章",
                            color = fg.copy(alpha = 0.45f), fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (range != null) {
                            Text("$page / ${pages.pages.size}", color = fg.copy(alpha = 0.45f), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(with(density) { navBottom.toDp() }))
                }
            }
        }
    }
}

// ================================ 上下滚动 ================================

@Composable
private fun ScrollReader(
    st: ReaderState,
    vm: ReaderViewModel,
    prefs: ReaderPrefs,
    fg: Color,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }

    // 切换章节后恢复位置 / 回到顶部
    LaunchedEffect(st.version) {
        val r = st.restore
        if (r != null) listState.scrollToItem(r.first, r.second.coerceAtLeast(0))
        else if (st.startAtEnd) listState.scrollToItem(st.paragraphs.size)
        else listState.scrollToItem(0)
    }
    // 停止滚动时记录进度
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                vm.position = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                vm.saveProgress()
            }
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(chromeVisible) { detectTapGestures(onTap = { onToggleChrome() }) }) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = statusTop + 16.dp, bottom = 96.dp),
        ) {
            item {
                Text(
                    st.chapters.getOrNull(st.index)?.title.orEmpty(),
                    color = fg,
                    fontSize = (prefs.fontSize + 4).sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (prefs.fontSize + 12).sp,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }
            items(st.paragraphs) { p ->
                Text(
                    "　　$p",
                    color = fg,
                    fontSize = prefs.fontSize.sp,
                    lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { vm.prev() }, enabled = st.index > 0, modifier = Modifier.weight(1f)) { Text("上一章") }
                    OutlinedButton(onClick = { vm.next() }, enabled = st.index < st.chapters.size - 1, modifier = Modifier.weight(1f)) { Text("下一章") }
                }
            }
        }
        if (!chromeVisible) {
            Text(
                "${st.index + 1} / ${st.chapters.size}",
                color = fg.copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp),
            )
        }
    }
}

// ================================ 设置 ================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(prefs: ReaderPrefs, onDismiss: () -> Unit) {
    val repo = LocalContext.current.graph.readerPrefs
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("翻页方式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageMode.entries.forEach { m ->
                    FilterChip(
                        selected = prefs.pageMode == m,
                        onClick = { repo.update { it.copy(pageMode = m) } },
                        label = { Text(m.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("字号 ${prefs.fontSize}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = prefs.fontSize.toFloat(),
                onValueChange = { v -> repo.update { it.copy(fontSize = v.toInt()) } },
                valueRange = 12f..30f,
                steps = 17,
            )

            Text("行距 %.1f".format(prefs.lineHeight), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = prefs.lineHeight,
                onValueChange = { v -> repo.update { it.copy(lineHeight = (v * 10).toInt() / 10f) } },
                valueRange = 1.2f..2.6f,
                steps = 13,
            )

            Spacer(Modifier.height(8.dp))
            Text("背景", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { t ->
                    FilterChip(
                        selected = prefs.theme == t,
                        onClick = { repo.update { p -> p.copy(theme = t) } },
                        label = { Text(t.label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("阅读时保持屏幕常亮", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = prefs.keepScreenOn, onCheckedChange = { v -> repo.update { it.copy(keepScreenOn = v) } })
            }
            Text(
                "左右翻页：点击屏幕左侧上一页、右侧下一页，中间呼出菜单",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun OutlinedButtonSmall(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) { Text(text, fontSize = 12.sp) }
}

@Composable
private fun BottomAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ================================ PDF ================================

@Composable
private fun PdfReaderScreen(file: File, slide: Boolean, onBack: () -> Unit) {
    var chromeVisible by remember { mutableStateOf(true) }
    var pageCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val renderer = remember(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(fd).also { pageCount = it.pageCount }
        }.onFailure { error = it.message ?: "无法打开 PDF" }.getOrNull()
    }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    val pagerState = rememberPagerState { pageCount }

    Box(Modifier.fillMaxSize().background(Color(0xFF303030))) {
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
            slide -> HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { off ->
                        val w = size.width
                        when {
                            off.x < w / 3f -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            off.x > w * 2f / 3f -> scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            else -> chromeVisible = !chromeVisible
                        }
                    }
                },
            ) { index ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PdfPage(renderer, mutex, index)
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                },
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items((0 until pageCount).toList()) { index -> PdfPage(renderer, mutex, index) }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(
                        file.nameWithoutExtension,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    Text(
                        if (slide) "${pagerState.currentPage + 1} / $pageCount" else "$pageCount 页",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer?, mutex: Mutex, index: Int) {
    val density = LocalDensity.current
    val widthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    val bitmap by produceState<Bitmap?>(null, renderer, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            renderer ?: return@withContext null
            runCatching {
                mutex.withLock {
                    renderer.openPage(index).use { page ->
                        val h = (widthPx.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = "第 ${index + 1} 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    } else {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.707f).padding(vertical = 4.dp).background(Color(0xFF424242)),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White) }
    }
}

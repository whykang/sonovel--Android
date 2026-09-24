package com.wang.sonovel.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wang.sonovel.data.LocalBook
import com.wang.sonovel.download.DownloadTask
import com.wang.sonovel.download.TaskStatus
import com.wang.sonovel.graph
import com.wang.sonovel.ui.components.BookCover
import com.wang.sonovel.ui.components.ConfirmDialog
import com.wang.sonovel.ui.components.EmptyState
import com.wang.sonovel.ui.components.Pill
import com.wang.sonovel.ui.components.SectionTitle
import com.wang.sonovel.ui.components.formatSize
import com.wang.sonovel.ui.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(onRead: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val tasks by g.downloads.tasks.collectAsStateWithLifecycle()
    val books by g.library.books.collectAsStateWithLifecycle()
    val snack = rememberSnack()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<LocalBook?>(null) }

    LaunchedEffect(Unit) { g.library.refresh() }

    val progressVersion by g.progress.version.collectAsStateWithLifecycle()

    /** 已读进度百分比，0 表示尚未开始 */
    fun progressOf(b: LocalBook): Int {
        progressVersion // 进度变化时触发重组
        return g.progress.get(b.file)?.percent ?: 0
    }

    fun openExternal(b: LocalBook) {
        try {
            context.startActivity(Intent.createChooser(g.library.openIntent(b.file), "打开方式").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            snack("未找到可打开 ${b.format.label} 的应用，请安装电子书阅读器")
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Text(
            "书架", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        if (tasks.isEmpty() && books.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Outlined.LibraryBooks, "书架空空如也",
                "在“搜索”页找到喜欢的书并下载，下载进度和已下载的书都会显示在这里",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tasks.isNotEmpty()) {
                item {
                    SectionTitle("下载任务") {
                        if (tasks.any { !it.status.active }) {
                            TextButton(onClick = { g.downloads.clearFinished() }) {
                                Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清除已结束")
                            }
                        }
                    }
                }
                items(tasks.asReversed(), key = { "t${it.id}" }) { t ->
                    TaskCard(
                        t,
                        onCancel = { g.downloads.cancel(t.id) },
                        onRetry = { g.downloads.retry(t.id) },
                        onRemove = { g.downloads.remove(t.id) },
                        onOpen = { t.output?.let(onRead) },
                    )
                }
            }
            if (books.isNotEmpty()) {
                item { SectionTitle("已下载 · ${books.size} 本") }
                items(books, key = { "b" + it.file.name }) { b ->
                    BookRow(
                        b,
                        onOpen = { onRead(b.file) },
                        onOpenExternal = { openExternal(b) },
                        progress = progressOf(b),
                        onShare = {
                            context.startActivity(Intent.createChooser(g.library.shareIntent(b.file), "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        onExport = {
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    runCatching { "已保存至 " + g.library.copyToPublic(b.file) }
                                        .getOrElse { "保存失败：${it.message}" }
                                }
                                snack(msg)
                            }
                        },
                        onDelete = { confirmDelete = b },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    confirmDelete?.let { b ->
        ConfirmDialog(
            title = "删除《${b.bookName}》？",
            text = "将从书架删除该文件（已保存到“下载/SoNovel”的副本不受影响）。",
            confirm = "删除",
            onDismiss = { confirmDelete = null },
            onConfirm = { g.progress.clear(b.file); g.library.delete(b) },
        )
    }
}

@Composable
private fun TaskCard(t: DownloadTask, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    val failed = t.status == TaskStatus.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = t.status == TaskStatus.DONE, onClick = onOpen),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            BookCover(t.coverUrl, t.bookName, width = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.bookName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Pill(t.request.format.label)
                }
                Text(
                    listOf(t.author, t.sourceName, t.request.rangeLabel).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                when (t.status) {
                    TaskStatus.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${t.done}/${t.total} 章" + (if (t.failed > 0) " · 失败 ${t.failed}" else "") +
                                (if (t.speed > 0) " · %.1f 章/秒".format(t.speed) else ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TaskStatus.PREPARING, TaskStatus.EXPORTING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                        Spacer(Modifier.height(4.dp))
                        Text(t.message ?: t.status.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text(
                        t.status.label + (t.message?.let { "：$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (t.status) {
                            TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                            TaskStatus.DONE -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column {
                if (t.status.active) {
                    IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "取消") }
                } else {
                    if (t.status != TaskStatus.DONE || t.failed > 0) {
                        IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, "重试") }
                    }
                    IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "移除任务") }
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    b: LocalBook,
    progress: Int,
    onOpen: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(b.cover, b.bookName, width = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.bookName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.author.isNotBlank()) Text(b.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(b.format.label)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatSize(b.size) + " · " + DateUtils.getRelativeTimeSpanString(b.modified),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
                if (progress > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已读 $progress%",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("阅读") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("用其他应用打开") }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) }, onClick = { menu = false; onOpenExternal() })
                DropdownMenuItem(text = { Text("分享") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("保存到 下载/SoNovel") }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) }, onClick = { menu = false; onExport() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

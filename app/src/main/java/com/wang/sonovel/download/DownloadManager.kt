package com.wang.sonovel.download

import android.content.Context
import androidx.core.content.ContextCompat
import com.wang.sonovel.core.BookParser
import com.wang.sonovel.core.ChapterParser
import com.wang.sonovel.core.ChapterProcessor
import com.wang.sonovel.core.CoverFetcher
import com.wang.sonovel.core.SourceContext
import com.wang.sonovel.core.TocParser
import com.wang.sonovel.core.export.Exporters
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.ChapterContent
import com.wang.sonovel.data.ChapterRef
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.LibraryRepository
import com.wang.sonovel.data.RuleRepository
import com.wang.sonovel.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadRequest(
    val url: String,
    val ruleKey: String?,
    val book: BookInfo? = null,
    /** 要下载的章节；为 null 表示全本 */
    val chapters: List<ChapterRef>? = null,
    val rangeLabel: String = "全本",
    val format: ExportFormat,
    /** 目标语言；null 表示使用设置 */
    val language: String? = null,
    val concurrency: Int? = null,
)

enum class TaskStatus(val label: String) {
    QUEUED("排队中"), PREPARING("解析中"), DOWNLOADING("下载中"), EXPORTING("生成中"),
    DONE("已完成"), FAILED("失败"), CANCELLED("已取消");

    val active get() = this == QUEUED || this == PREPARING || this == DOWNLOADING || this == EXPORTING
}

data class DownloadTask(
    val id: Long,
    val request: DownloadRequest,
    val bookName: String,
    val author: String,
    val coverUrl: String? = null,
    val sourceName: String = "",
    val status: TaskStatus = TaskStatus.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
    val output: File? = null,
    val failedChapters: List<String> = emptyList(),
    val speed: Double = 0.0,
) {
    val progress: Float get() = if (total <= 0) 0f else (done + failed).toFloat() / total
}

/**
 * 下载队列：逐本下载（避免触发源站限流），每本书内部按并发数抓取章节；章节缓存支持断点续传。
 */
class DownloadManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val rules: RuleRepository,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 任务结束（成功或失败）事件，用于发送通知；成功的任务随后会自动从列表中移除 */
    private val _finished = MutableSharedFlow<DownloadTask>(extraBufferCapacity = 32)
    val finished: SharedFlow<DownloadTask> = _finished.asSharedFlow()
    private val ids = AtomicLong(1)
    private var worker: Job? = null
    private var currentJob: Job? = null
    private var currentId: Long = -1

    /** 公共目录副本保存失败时的提示（如缺少存储权限） */
    var onPublicCopyFailed: ((String) -> Unit)? = null

    val hasActive: Boolean get() = _tasks.value.any { it.status.active }

    fun enqueue(req: DownloadRequest, bookName: String, author: String, coverUrl: String?): Long {
        val id = ids.getAndIncrement()
        val rule = req.ruleKey?.let { rules.byKey(it) } ?: rules.matchByUrl(req.url)
        _tasks.update {
            it + DownloadTask(
                id = id, request = req, bookName = bookName.ifBlank { "解析中…" }, author = author,
                coverUrl = coverUrl, sourceName = rule?.displayName.orEmpty(),
            )
        }
        startWorker()
        return id
    }

    fun cancel(id: Long) {
        if (id == currentId) currentJob?.cancel()
        update(id) { if (it.status.active) it.copy(status = TaskStatus.CANCELLED, message = null) else it }
    }

    fun retry(id: Long) {
        update(id) { it.copy(status = TaskStatus.QUEUED, message = null, failed = 0, failedChapters = emptyList()) }
        startWorker()
    }

    fun remove(id: Long) {
        cancel(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() = _tasks.update { list -> list.filter { it.status.active } }

    private fun update(id: Long, block: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }

    private fun get(id: Long) = _tasks.value.firstOrNull { it.id == id }

    @Synchronized
    private fun startWorker() {
        runCatching {
            ContextCompat.startForegroundService(context, android.content.Intent(context, DownloadService::class.java))
        }
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _tasks.value.firstOrNull { it.status == TaskStatus.QUEUED } ?: break
                currentId = next.id
                val job = launch { runTask(next.id) }
                currentJob = job
                job.join()
                currentId = -1
            }
        }
    }

    private suspend fun runTask(id: Long) {
        val task = get(id) ?: return
        val req = task.request
        try {
            val s0 = settings.current
            val rule = req.ruleKey?.let { rules.byKey(it) } ?: rules.matchByUrl(req.url)
                ?: throw IOException("找不到与链接匹配的书源：${req.url}")
            val s = s0.copy(
                extName = req.format.ext,
                language = req.language ?: s0.language,
                concurrency = req.concurrency ?: s0.concurrency,
            )
            val ctx = SourceContext(rule, s)
            update(id) { it.copy(status = TaskStatus.PREPARING, message = "正在解析书籍详情…", sourceName = rule.displayName) }

            val langChanged = req.language != null && req.language != s0.targetLanguage
            val book = (if (langChanged) null else req.book) ?: BookParser(ctx).parse(req.url)
            update(id) { it.copy(bookName = book.bookName, author = book.author, coverUrl = it.coverUrl ?: book.coverUrl, message = "正在解析章节目录…") }

            val toc = req.chapters ?: TocParser(ctx).parseAll(req.url)
            if (toc.isEmpty()) throw IOException("源站章节目录为空，中止下载（可能有反爬限制）")

            val cacheDir = File(context.cacheDir, "chapters/" + md5("${req.url}|${ctx.targetLang}")).apply { mkdirs() }
            fun cacheFile(c: ChapterRef) = File(cacheDir, "%06d.txt".format(c.order))

            val total = toc.size
            val pending = toc.filter { !cacheFile(it).exists() }
            val done = AtomicInteger(total - pending.size)
            val failedList = java.util.Collections.synchronizedList(ArrayList<String>())
            val crawl = ctx.crawl
            val maxConcurrent = (if (crawl.concurrency <= 0) minOf(50, total) else minOf(crawl.concurrency, total)).coerceIn(1, 100)
            val startTime = System.currentTimeMillis()
            val startDone = done.get()
            update(id) { it.copy(status = TaskStatus.DOWNLOADING, done = done.get(), total = total, message = "最大并发 $maxConcurrent") }

            val parser = ChapterParser(ctx)
            val processor = ChapterProcessor(ctx)
            val lastUi = AtomicLong(0)
            fun report(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUi.get() < 250) return
                lastUi.set(now)
                val secs = (now - startTime) / 1000.0
                val speed = if (secs > 0) (done.get() - startDone) / secs else 0.0
                update(id) { it.copy(done = done.get(), failed = failedList.size, speed = speed) }
            }

            coroutineScope {
                val sem = Semaphore(maxConcurrent)
                for (c in pending) {
                    launch(Dispatchers.IO) {
                        sem.withPermit {
                            val content = fetchWithRetry(ctx, parser, processor, c)
                            if (content != null) {
                                cacheFile(c).writeText(content.title + "\n" + content.html)
                                done.incrementAndGet()
                            } else {
                                failedList += c.title
                            }
                            report()
                        }
                    }
                }
            }
            report(force = true)
            if (done.get() == 0) throw IOException("全部章节下载失败，可能被源站限流，请稍后重试或更换书源")

            update(id) { it.copy(status = TaskStatus.EXPORTING, message = "正在生成 ${req.format.label}…") }
            val coverUrl = if (s.fetchBetterCover && !rule.needProxy) {
                runCatching { CoverFetcher.best(book, s) }.getOrNull() ?: book.coverUrl
            } else book.coverUrl
            val coverBytes = coverUrl?.let { u ->
                runCatching { CoverFetcher.download(u, s) }.getOrNull()?.takeIf { it.size > 1024 }
            }
            val output = library.outputFile(book, req.format)
            val finalBook = book.copy(coverUrl = coverUrl)
            val chapters = {
                toc.asSequence().mapNotNull { c ->
                    val f = cacheFile(c)
                    if (!f.exists()) null else {
                        val text = f.readText()
                        val nl = text.indexOf('\n')
                        if (nl < 0) null else ChapterContent(c.order, text.substring(0, nl), text.substring(nl + 1))
                    }
                }
            }
            val charset = runCatching { Charset.forName(s.txtEncoding.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
            withContext(Dispatchers.IO) {
                Exporters.export(req.format, finalBook, chapters, coverBytes, output, charset)
            }
            library.saveCover(output, coverBytes)
            library.refresh()

            var publicMsg: String? = null
            if (s.saveToPublic) {
                publicMsg = runCatching { "已保存至 " + library.copyToPublic(output) }
                    .getOrElse { e ->
                        onPublicCopyFailed?.invoke(e.message ?: "")
                        "未能保存到公共下载目录：${e.message}"
                    }
            }
            if (failedList.isEmpty()) cacheDir.deleteRecursively()
            val msg = buildString {
                val secs = (System.currentTimeMillis() - startTime) / 1000.0
                append("耗时 %.1f 秒".format(secs))
                if (failedList.isNotEmpty()) append("，${failedList.size} 章失败，可点击重试补全")
                publicMsg?.let { append("\n").append(it) }
            }
            update(id) {
                it.copy(
                    status = TaskStatus.DONE, output = output, message = msg, coverUrl = coverUrl ?: it.coverUrl,
                    failed = failedList.size, failedChapters = failedList.toList(), done = done.get(),
                )
            }
            get(id)?.let { _finished.tryEmit(it) }
            // 下载完成后自动清除任务，书籍直接进入书架；有失败章节时保留以便补全
            if (failedList.isEmpty()) {
                _tasks.update { list -> list.filterNot { it.id == id } }
            }
        } catch (e: CancellationException) {
            update(id) { if (it.status.active) it.copy(status = TaskStatus.CANCELLED) else it }
            throw e
        } catch (e: Throwable) {
            update(id) { it.copy(status = TaskStatus.FAILED, message = e.message ?: e.javaClass.simpleName) }
            get(id)?.let { _finished.tryEmit(it) }
        }
    }

    /** 抓取单章，失败时按递增间隔重试；未启用重试时直接抛出以中断整本下载 */
    private suspend fun fetchWithRetry(
        ctx: SourceContext, parser: ChapterParser, processor: ChapterProcessor, c: ChapterRef,
    ): ChapterContent? {
        var lastError: Throwable
        try {
            val raw = parser.fetchContent(c.url, ctx.randomInterval())
            return processor.process(c.order, c.title, raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e
            if (!ctx.crawl.enableRetry) throw IOException("【${c.title}】下载出错：${e.message}（未启用重试，已中断）")
        }
        for (attempt in 1..ctx.crawl.maxRetries) {
            try {
                val raw = parser.fetchContent(c.url, ctx.randomInterval(retry = true) * attempt)
                return processor.process(c.order, c.title, raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        android.util.Log.w("SoNovel", "章节下载失败: ${c.title} ${c.url}: ${lastError.message}")
        return null
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

package com.wang.sonovel.core

import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.ChapterRef
import com.wang.sonovel.data.CrawlConfig
import com.wang.sonovel.data.Rule
import com.wang.sonovel.data.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlin.random.Random

/** 某书源在当前设置下的运行上下文 */
class SourceContext(val rule: Rule, val settings: AppSettings) {
    val client: OkHttpClient = Http.client(settings, unsafe = rule.ignoreSsl)
    val crawl: CrawlConfig = CrawlConfig.of(settings, rule)
    val sourceLang: String = rule.language ?: AppSettings.systemLanguage()
    val targetLang: String = settings.targetLanguage

    fun randomInterval(retry: Boolean = false): Long {
        val min = if (retry) crawl.retryMinInterval else crawl.minInterval
        val max = if (retry) crawl.retryMaxInterval else crawl.maxInterval
        return if (max > min) Random.nextLong(min.toLong(), max.toLong()) else min.coerceAtLeast(0).toLong()
    }

    fun fetchDocument(url: String, timeout: Int?, baseUri: String?, what: String): Document {
        val page = Http.get(client, url, timeout)
        val doc = page.document(baseUri)
        return bypassCloudflareIfNeeded(doc, url, settings, what)
    }
}

private fun resolve(base: String, href: String?): String? {
    if (href.isNullOrBlank()) return href
    return runCatching { URI(base).resolve(href.trim()).toString() }.getOrDefault(href)
}

// ================================ 搜索 ================================

class SearchParser(private val ctx: SourceContext) {
    private val rule = ctx.rule

    suspend fun search(keyword: String): List<SearchResult> {
        val r = rule.search ?: return emptyList()
        if (rule.disabled) return emptyList()
        val searchUrl = processUrl(r.url.orEmpty(), keyword)
        val rb = Request.Builder().url(searchUrl)
            .header("User-Agent", RandomUA.generate())
            .header("Referer", Http.referer(searchUrl))
        if (!r.cookies.isNullOrBlank() && r.cookies != "{}") rb.header("Cookie", r.cookies!!)
        if ("post".equals(r.method, true)) rb.post(Http.buildForm(r.data, keyword))

        val page = Http.execute(ctx.client, rb.build(), r.timeout)
        val body = processResultWithJs(page.text, r.result)
        var doc = Jsoup.parse(body, r.baseUri ?: page.url)
        doc = bypassCloudflareIfNeeded(doc, searchUrl, ctx.settings, "搜索页")

        val first = results(doc, r, page.url)
        if (r.nextPage.isNullOrBlank()) return first.limit()

        val nextEls = Extractor.select(doc, r.nextPage)
        if (nextEls.isEmpty()) return first.limit()
        val urls = LinkedHashSet<String>()
        for (e in nextEls) {
            val href = e.absUrl("href")
            if (href.isNotBlank()) urls += runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        }
        urls.remove(page.url)
        urls.remove(searchUrl)
        val more = coroutineScope {
            val sem = Semaphore(4)
            urls.map { u ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching {
                            val p = Http.get(ctx.client, u, r.timeout)
                            val d = Jsoup.parse(processResultWithJs(p.text, r.result), r.baseUri ?: p.url)
                            results(d, r, p.url)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
        return (first + more).limit()
    }

    private fun List<SearchResult>.limit(): List<SearchResult> {
        val n = ctx.settings.searchLimit
        return if (n <= 0) this else take(n)
    }

    private fun results(doc: Document, r: Rule.Search, pageUrl: String): List<SearchResult> {
        val resultEls = Extractor.select(doc, stripJs(r.result))
        // 部分书源完全匹配时直接跳转到详情页
        if (resultEls.isEmpty()) {
            val bookRule = rule.book
            if (bookRule != null && Extractor.select(doc, bookRule.bookName).isNotEmpty()) {
                val book = runCatching { BookParser(ctx).parseDocument(doc, pageUrl) }.getOrNull()
                    ?: return emptyList()
                return listOf(
                    SearchResult(
                        sourceKey = rule.key, sourceName = rule.displayName, url = pageUrl,
                        bookName = book.bookName, author = book.author, latestChapter = book.latestChapter,
                        lastUpdateTime = book.lastUpdateTime, category = book.category, status = book.status,
                    )
                )
            }
            return emptyList()
        }
        val list = ArrayList<SearchResult>()
        val limit = ctx.settings.searchLimit.let { if (it <= 0) Int.MAX_VALUE else it }
        for (el in resultEls) {
            if (list.size >= limit) break
            val bookName = runCatching { Extractor.extract(el, r.bookName) }.getOrNull()
            if (bookName.isNullOrBlank()) continue
            val href = Extractor.extract(el, r.bookName, ContentType.ATTR_HREF).orEmpty()
            if (href.isBlank()) continue
            fun opt(q: String?) = runCatching { Extractor.extract(el, q) }.getOrNull()?.trim()?.ifBlank { null }
            val sr = SearchResult(
                sourceKey = rule.key,
                sourceName = rule.displayName,
                url = href,
                bookName = bookName.trim(),
                author = opt(r.author),
                category = opt(r.category),
                latestChapter = opt(r.latestChapter),
                lastUpdateTime = opt(r.lastUpdateTime),
                status = opt(r.status),
                wordCount = opt(r.wordCount),
            )
            list += ChineseConverter.convert(sr, ctx.sourceLang, ctx.targetLang)
        }
        return list
    }

    companion object {
        /** url 含 @js: 时，JS 接收 keyword 返回完整 URL；否则直接格式化 */
        fun processUrl(url: String, keyword: String): String =
            if (url.contains("@js:")) JsEngine.call(url.substringAfter("@js:"), keyword)
            else url.replace("%s", keyword)

        /** result 含 @js: 时，JS 接收响应体并返回转换后的 HTML */
        fun processResultWithJs(body: String, result: String?): String =
            if (result != null && result.contains("@js:")) JsEngine.call(result.substringAfter("@js:"), body)
            else body

        fun stripJs(result: String?): String? =
            if (result != null && result.contains("@js:")) result.substringBefore("@js:") else result
    }
}

// ================================ 详情 ================================

class BookParser(private val ctx: SourceContext) {

    fun parse(url: String): BookInfo {
        val r = ctx.rule.book ?: Rule.Book()
        val doc = ctx.fetchDocument(url, r.timeout, r.baseUri, "详情页")
        return parseDocument(doc, url)
    }

    fun parseDocument(doc: Document, url: String): BookInfo {
        val r = ctx.rule.book ?: Rule.Book()
        fun x(q: String?) = runCatching { Extractor.extract(doc, q) }.getOrNull()?.trim().orEmpty()
        val bookName = x(r.bookName)
        val author = x(r.author).replace("作者：", "").replace("作者:", "").trim()
        if (bookName.isEmpty() || author.isEmpty()) {
            throw IOException("详情页书名或作者为空，可能书源已失效或被限流")
        }
        val cover = resolve(url, x(r.coverUrl).ifBlank { null })
        val book = BookInfo(
            url = url,
            bookName = bookName,
            author = author,
            intro = x(r.intro).let { cleanBlank(it) }.ifBlank { null },
            category = x(r.category).ifBlank { null },
            coverUrl = cover,
            latestChapter = x(r.latestChapter).ifBlank { null },
            latestChapterUrl = resolve(url, x(r.latestChapterUrl).ifBlank { null }),
            lastUpdateTime = x(r.lastUpdateTime).replace(Regex("(更新时间|最后更新)[：:]"), "").trim().ifBlank { null },
            status = x(r.status).ifBlank { null },
        )
        return ChineseConverter.convert(book, ctx.sourceLang, ctx.targetLang)
    }
}

// ================================ 目录 ================================

class TocParser(private val ctx: SourceContext) {

    suspend fun parseAll(url: String): List<ChapterRef> {
        val tocRule = ctx.rule.toc ?: throw IOException("书源缺少目录规则")
        val bookRule = ctx.rule.book
        val idPattern = bookRule?.url?.substringBefore("@js:")?.takeIf { it.isNotBlank() }
        val id = idPattern?.let { runCatching { Regex(it).find(url)?.groupValues?.getOrNull(1) }.getOrNull() }
        val baseUri = tocRule.baseUri?.let { if (id != null) it.replace("%s", id) else it }
        val startUrl = if (!tocRule.url.isNullOrBlank()) tocRule.url!!.replace("%s", id.orEmpty()) else url

        val urls = LinkedHashSet<String>().apply { add(startUrl) }
        if (!tocRule.nextPage.isNullOrBlank()) {
            val doc = ctx.fetchDocument(startUrl, tocRule.timeout, baseUri, "目录页")
            extractPaginationUrls(urls, doc, tocRule, baseUri)
        }
        return parseToc(urls.toList(), tocRule, baseUri)
    }

    private suspend fun extractPaginationUrls(urls: LinkedHashSet<String>, first: Document, r: Rule.Toc, baseUri: String?) {
        var doc = first
        val els = Extractor.select(doc, r.nextPage)
        // 下拉菜单一次性获取所有分页
        if (els.isNotEmpty() && els.hasAttr("value")) {
            val attr = if (els.eachAttr("href").isEmpty()) "value" else "href"
            for (e in els) {
                val u = e.absUrl(attr).ifBlank { e.attr(attr) }
                if (u.isBlank()) continue
                urls.remove(u); urls.add(u)
            }
            return
        }
        // 逐页点击“下一页”
        var guard = 0
        while (guard++ < 500) {
            val href = Extractor.extract(doc, r.nextPage, ContentType.ATTR_HREF)
            val next = if (!href.isNullOrBlank()) href else Extractor.extract(doc, r.nextPage, ContentType.ATTR_VALUE)
            if (next.isNullOrBlank() || !next.startsWith("http") || next in urls) break
            urls.add(next)
            delay(ctx.randomInterval())
            doc = ctx.fetchDocument(next, r.timeout, baseUri, "目录页")
        }
    }

    private fun elementsOf(doc: Document, r: Rule.Toc, baseUri: String?): List<Element> {
        if (!r.list.isNullOrBlank()) {
            val html = Extractor.extract(doc, r.list, ContentType.HTML).orEmpty()
            val tocDoc = Jsoup.parse(html, baseUri ?: doc.location())
            return Extractor.select(tocDoc, r.item)
        }
        return Extractor.select(doc, r.item)
    }

    private suspend fun parseToc(urls: List<String>, r: Rule.Toc, baseUri: String?): List<ChapterRef> {
        val pages: List<List<Element>?> = coroutineScope {
            val sem = Semaphore(5)
            urls.map { u ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching {
                            val doc = ctx.fetchDocument(u, r.timeout, baseUri, "目录页")
                            elementsOf(doc, r, baseUri)
                        }.getOrElse { if (urls.size == 1) throw it else null }
                    }
                }
            }.awaitAll()
        }
        // 与桌面版 TocList 一致：同名章节保留靠后的一个
        val map = LinkedHashMap<String, ChapterRef>()
        var order = 1
        for (els in pages) {
            if (els == null) continue
            val ordered = if (r.isDesc) els.asReversed() else els
            for (el in ordered) {
                val title = el.text().trim()
                val href = el.absUrl("href").ifBlank { el.attr("href") }
                if (href.isBlank()) continue
                val link = runCatching { Extractor.runDsl(r.nextPage, href) }.getOrDefault(href)
                map.remove(title)
                map[title] = ChapterRef(order++, title, link)
            }
        }
        return map.values.mapIndexed { i, c -> c.copy(order = i + 1) }
    }
}

// ================================ 章节 ================================

class ChapterParser(private val ctx: SourceContext) {
    private val r = ctx.rule.chapter ?: Rule.Chapter()

    /** 抓取章节原始正文 HTML（含分页合并） */
    suspend fun fetchContent(url: String, interval: Long): String {
        delay(interval)
        return if (r.nextPage.isNullOrBlank()) fetchSingle(url) else fetchPaginated(url, interval)
    }

    private fun fetchSingle(url: String): String {
        val doc = ctx.fetchDocument(url, r.timeout, r.baseUri, "章节页")
        val content = Extractor.extract(doc, r.content, ContentType.HTML)
        if (content.isNullOrBlank()) throw IOException("正文内容为空，可能被限流")
        return content
    }

    private suspend fun fetchPaginated(start: String, interval: Long): String {
        var next = start
        val sb = StringBuilder()
        val visited = HashSet<String>()
        var pages = 0
        while (pages++ < 100 && visited.add(next)) {
            val doc = ctx.fetchDocument(next, r.timeout, r.baseUri, "章节页")
            val content = Extractor.extract(doc, r.content, ContentType.HTML)
            if (content.isNullOrBlank()) throw IOException("正文内容为空，可能被限流")
            sb.append(content)

            val nextEls = Extractor.select(doc, r.nextPage)
            val candidate: String? = if (r.nextPageInJs != null) {
                resolve(doc.location(), Extractor.extract(doc, r.nextPageInJs, ContentType.HTML))
            } else {
                nextEls.firstOrNull()?.absUrl("href")
            }
            if (candidate.isNullOrBlank()) break
            val endByRule = r.nextChapterLink != null && runCatching { candidate.matches(Regex(r.nextChapterLink!!)) }.getOrDefault(false)
            val genericEnd = !candidate.matches(Regex(".*[-_]\\d\\.html")) &&
                nextEls.text().matches(Regex(".*(下一章|没有了|>>|书末页).*"))
            if (endByRule || genericEnd) break
            next = candidate
            delay(interval)
        }
        return sb.toString()
    }
}

/** hutool StrUtil.cleanBlank：去除空白字符（保留英文单词间的单个空格） */
fun cleanBlank(s: String): String {
    val sb = StringBuilder(s.length)
    var pendingSpace = false
    for (c in s) {
        val blank = c.isWhitespace() || Character.isSpaceChar(c) || c == '﻿' || c == '‪' ||
            c == '\u0000' || c == 'ㅤ' || c == '⠀' || c == '᠎'
        if (blank) {
            pendingSpace = true
            continue
        }
        if (pendingSpace && sb.isNotEmpty()) {
            val prev = sb[sb.length - 1]
            if (prev.isAsciiWord() && c.isAsciiWord()) sb.append(' ')
        }
        pendingSpace = false
        sb.append(c)
    }
    return sb.toString()
}

private fun Char.isAsciiWord() = this < '\u0080' && (isLetterOrDigit() || this == ',' || this == '.' || this == '!' || this == '?')

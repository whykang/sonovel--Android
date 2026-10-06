package com.wang.sonovel.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

class ReaderChapter(
    val title: String,
    internal val locator: String = "",
    internal val start: Int = 0,
    internal val end: Int = 0,
)

/**
 * 阅读器内容源：直接读取书籍文件（不额外占用存储），章节正文按需加载。
 * 既支持本应用导出的文件，也支持用户导入的外部 EPUB / TXT。
 */
abstract class BookReadSource(val chapters: List<ReaderChapter>) : AutoCloseable {
    abstract fun paragraphs(index: Int): List<String>
    override fun close() {}

    companion object {
        /** 打开本地书籍文件（PDF 由 PdfReader 单独处理） */
        fun open(file: File): BookReadSource {
            if (!file.exists()) throw IOException("文件不存在：${file.name}")
            return when (file.extension.lowercase()) {
                "epub" -> openEpub(file)
                "zip" -> openHtmlZip(file)
                "txt" -> openTxt(file)
                else -> throw IOException("暂不支持阅读 ${file.extension} 格式")
            }
        }
    }
}

/** 从 HTML 文档中取出段落文字；没有 <p> 时按换行切分 */
private fun paragraphsOf(doc: Document): List<String> {
    doc.select("script, style, h1, h2, h3").remove()
    val ps = doc.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
    if (ps.size >= 2) return ps
    // 无 <p> 的排版：用 <br> 和块级标签作为换行
    doc.select("br").forEach { it.after(TextNode("\n")) }
    doc.select("div, p, li, h4, h5, h6, blockquote").forEach { it.appendChild(TextNode("\n")) }
    return (doc.body()?.wholeText() ?: "").lines().map { it.trim().trim('　') }.filter { it.isNotEmpty() }
}

// ================================ EPUB ================================

/** EPUB 元数据：导入本地书籍时用于命名和提取封面 */
class EpubMeta(val title: String?, val author: String?, val cover: ByteArray?)

private fun ZipFile.xml(name: String): Document? =
    getEntry(name)?.let { e -> getInputStream(e).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) } }

private fun ZipFile.opfPath(): String =
    xml("META-INF/container.xml")?.select("rootfile")?.attr("full-path")?.ifBlank { null }
        ?: entries().asSequence().firstOrNull { it.name.endsWith(".opf") }?.name
        ?: throw IOException("EPUB 缺少 content.opf")

/** 把 href 解析为压缩包内的路径：去锚点、URL 解码、处理 ../ */
private fun resolveEntry(baseDir: String, href: String): String {
    val raw = href.substringBefore('#')
    val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    val parts = ArrayList<String>()
    for (seg in (baseDir + decoded).split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += seg
        }
    }
    return parts.joinToString("/")
}

/** 按“去掉命名空间前缀后的标签名”查找元素，兼容 <opf:item>、<item> 等写法 */
private fun Document.byName(local: String): List<org.jsoup.nodes.Element> =
    allElements.filter { it.tagName().substringAfterLast(':').equals(local, true) }

private fun ZipFile.bytes(name: String): ByteArray? =
    getEntry(name)?.let { e -> getInputStream(e).use { it.readBytes() } }

/** 从一个 XHTML 页面里取第一张图片（封面页通常只有一张图） */
private fun ZipFile.firstImageOf(pagePath: String): ByteArray? {
    val entry = getEntry(pagePath) ?: return null
    val doc = getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
    val dir = pagePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val src = doc.select("img[src]").firstOrNull()?.attr("src")
        ?: doc.select("image").firstOrNull()?.let { it.attr("xlink:href").ifBlank { it.attr("href") } }
    return src?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { bytes(resolveEntry(dir, it)) }
}

fun readEpubMeta(file: File): EpubMeta = ZipFile(file).use { zip ->
    val opfPath = zip.opfPath()
    val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val opf = zip.xml(opfPath) ?: throw IOException("无法读取 content.opf")
    val title = opf.byName("title").firstOrNull()?.text()?.trim()?.ifBlank { null }
    val author = opf.byName("creator").firstOrNull()?.text()?.trim()?.ifBlank { null }
    val items = opf.byName("item")
    fun isImage(it: org.jsoup.nodes.Element) =
        it.attr("media-type").startsWith("image/") || Regex("\\.(jpe?g|png|webp|gif)$", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("href"))
    fun load(item: org.jsoup.nodes.Element?): ByteArray? =
        item?.attr("href")?.takeIf { it.isNotBlank() }?.let { zip.bytes(resolveEntry(base, it)) }?.takeIf { it.size > 200 }

    val coverId = opf.byName("meta").firstOrNull { it.attr("name").equals("cover", true) }?.attr("content").orEmpty()
    // 依次尝试各种常见的封面标注方式
    val cover: ByteArray? =
        // 1. EPUB3：properties="cover-image"
        load(items.firstOrNull { it.attr("properties").contains("cover-image") })
        // 2. EPUB2：<meta name="cover" content="图片 id">（有的书 content 直接写文件路径）
            ?: load(items.firstOrNull { coverId.isNotBlank() && it.attr("id") == coverId && isImage(it) })
            ?: coverId.takeIf { it.contains('.') }?.let { zip.bytes(resolveEntry(base, it)) }
            // 3. meta 指向的是封面页而不是图片
            ?: items.firstOrNull { coverId.isNotBlank() && it.attr("id") == coverId && !isImage(it) }
                ?.attr("href")?.let { zip.firstImageOf(resolveEntry(base, it)) }
            // 4. <guide> 里 type="cover" 的封面页
            ?: opf.byName("reference").firstOrNull { it.attr("type").equals("cover", true) }
                ?.attr("href")?.takeIf { it.isNotBlank() }?.let { zip.firstImageOf(resolveEntry(base, it)) }
            // 5. 名字里带 cover 的图片
            ?: load(items.firstOrNull { isImage(it) && (it.attr("id") + it.attr("href")).contains("cover", true) })
            // 6. 正文第一页里的图片
            ?: opf.byName("itemref").firstOrNull()?.attr("idref")
                ?.let { id -> items.firstOrNull { it.attr("id") == id }?.attr("href") }
                ?.let { zip.firstImageOf(resolveEntry(base, it)) }
            // 7. 压缩包里名字带 cover 的图片（清单里没登记的情况）
            ?: zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && Regex("cover[^/]*\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
                ?.let { zip.bytes(it.name) }
    EpubMeta(title, author, cover)
}

private class EpubReadSource(
    private val zip: ZipFile,
    chapters: List<ReaderChapter>,
) : BookReadSource(chapters) {

    override fun paragraphs(index: Int): List<String> {
        val entry = zip.getEntry(chapters[index].locator) ?: return emptyList()
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
        return paragraphsOf(doc)
    }

    override fun close() = runCatching { zip.close() }.let {}
}

private fun openEpub(file: File): BookReadSource {
    val zip = ZipFile(file)
    try {
        val opfPath = zip.opfPath()
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = zip.xml(opfPath) ?: throw IOException("无法读取 content.opf")
        val manifest = opf.byName("item")

        // 优先用 toc.ncx 的目录（含章节名），否则退化为 spine 顺序
        val chapters = mutableListOf<ReaderChapter>()
        val seen = HashSet<String>()
        val ncxHref = manifest.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }?.attr("href").orEmpty()
        if (ncxHref.isNotBlank()) {
            val ncxPath = resolveEntry(base, ncxHref)
            val ncxBase = ncxPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            zip.xml(ncxPath)?.let { ncx ->
                for (np in ncx.byName("navPoint")) {
                    // 只取本条目自己的标题和链接（嵌套的子条目会各自遍历到）
                    val own = np.children()
                    val src = own.firstOrNull { it.tagName().substringAfterLast(':').equals("content", true) }?.attr("src") ?: continue
                    val title = own.firstOrNull { it.tagName().substringAfterLast(':').equals("navLabel", true) }?.text()?.trim().orEmpty()
                    val entryName = resolveEntry(ncxBase, src)
                    // 同一文件的多个锚点只保留第一个
                    if (zip.getEntry(entryName) != null && seen.add(entryName)) {
                        chapters += ReaderChapter(title.ifBlank { "第 ${chapters.size + 1} 章" }, entryName)
                    }
                }
            }
        }
        // 目录条目过少时（如只列了卷名），改用 spine 保证内容完整
        val spine = opf.byName("itemref").mapNotNull { ref ->
            manifest.firstOrNull { it.attr("id") == ref.attr("idref") }?.attr("href")?.takeIf { it.isNotBlank() }
        }.map { resolveEntry(base, it) }.filter { zip.getEntry(it) != null }
        if (chapters.isEmpty() || chapters.size * 3 < spine.size) {
            val titles = chapters.associate { it.locator to it.title }
            chapters.clear()
            for (entryName in spine) {
                if (spine.size > 3 && Regex("(^|/)(cover|nav|toc|titlepage)[^/]*$", RegexOption.IGNORE_CASE).containsMatchIn(entryName)) continue
                val title = titles[entryName] ?: zip.getInputStream(zip.getEntry(entryName)).use { Jsoup.parse(it, "UTF-8", "") }
                    .let { d -> d.selectFirst("h1, h2, h3")?.text() ?: d.title() }
                chapters += ReaderChapter(title.trim().ifBlank { "第 ${chapters.size + 1} 章" }, entryName)
            }
        }
        if (chapters.isEmpty()) throw IOException("EPUB 中没有找到章节")
        return EpubReadSource(zip, chapters)
    } catch (e: Throwable) {
        runCatching { zip.close() }
        throw e
    }
}

// ================================ HTML (zip) ================================

private class HtmlZipReadSource(
    private val zip: ZipFile,
    chapters: List<ReaderChapter>,
) : BookReadSource(chapters) {

    override fun paragraphs(index: Int): List<String> {
        val entry = zip.getEntry(chapters[index].locator) ?: return emptyList()
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
        doc.select(".bar").remove()
        return paragraphsOf(doc)
    }

    override fun close() = runCatching { zip.close() }.let {}
}

private fun openHtmlZip(file: File): BookReadSource {
    val zip = ZipFile(file)
    try {
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".html") && !it.name.endsWith("index.html") }
            .map { it.name }
            .sorted()
            .toList()
        val chapters = entries.map { name ->
            val title = zip.getInputStream(zip.getEntry(name)).use { Jsoup.parse(it, "UTF-8", "") }
                .let { d -> d.selectFirst("h1")?.text() ?: d.title() }
            ReaderChapter(title.trim().ifBlank { name }, name)
        }
        if (chapters.isEmpty()) throw IOException("压缩包中没有找到章节")
        return HtmlZipReadSource(zip, chapters)
    } catch (e: Throwable) {
        runCatching { zip.close() }
        throw e
    }
}

// ================================ TXT ================================

private class TxtReadSource(
    private val text: String,
    chapters: List<ReaderChapter>,
) : BookReadSource(chapters) {

    override fun paragraphs(index: Int): List<String> {
        val c = chapters[index]
        return text.substring(c.start, c.end)
            .lineSequence()
            .map { it.trim().trim('　') }
            .filter { it.isNotEmpty() }
            .toList()
    }
}

private val CHAPTER_TITLE = Regex(
    "^[\\s\\u3000]*(第[零〇一二两三四五六七八九十百千万\\d]{1,12}[章节節回卷集部篇话話][^\\n]{0,40}" +
        "|(楔子|序章|序言|序幕|引子|前言|后记|後記|尾声|尾聲|终章|終章|番外)[^\\n]{0,30}" +
        "|(?i:chapter|part)\\s*\\d+[^\\n]{0,40})$"
)

/** 每行的 (起始位置, 结束位置) */
private inline fun forEachLine(text: String, block: (start: Int, end: Int, index: Int) -> Unit) {
    var i = 0
    var n = 0
    while (i < text.length) {
        val nl = text.indexOf('\n', i).let { if (it == -1) text.length else it }
        block(i, nl, n++)
        i = nl + 1
    }
}

private fun openTxt(file: File): BookReadSource {
    val text = decodeText(file.readBytes())
    // 章节标记：标题行起始位置 to 标题
    val marks = ArrayList<Triple<Int, Int, String>>() // (标题行起点, 正文起点, 标题)

    val ownExport = text.startsWith("书名：")
    if (ownExport) {
        // 本应用导出的 TXT：正文段落以全角空格缩进，未缩进的非空行即章节名
        forEachLine(text) { s, e, n ->
            val line = text.substring(s, e)
            val t = line.trim()
            val header = n < 3 && (t.startsWith("书名：") || t.startsWith("作者：") || t.startsWith("简介："))
            if (t.isNotEmpty() && !line.startsWith('　') && !header) marks += Triple(s, minOf(e + 1, text.length), t)
        }
    }
    if (marks.size < 2) {
        marks.clear()
        // 外部 TXT：按常见章节名识别
        forEachLine(text) { s, e, _ ->
            if (e - s in 2..60) {
                val t = text.substring(s, e).trim().trim('　')
                if (t.length <= 50 && CHAPTER_TITLE.matches(t)) marks += Triple(s, minOf(e + 1, text.length), t)
            }
        }
    }

    val chapters = ArrayList<ReaderChapter>()
    if (marks.size >= 2) {
        // 第一个章节名之前的内容（简介、序等）单独成章
        if (!ownExport && text.substring(0, marks[0].first).isNotBlank()) {
            chapters += ReaderChapter("开始", "", 0, marks[0].first)
        }
        for ((i, m) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].first else text.length
            chapters += ReaderChapter(m.third, "", m.second, maxOf(end, m.second))
        }
    } else {
        // 识别不出章节：按固定长度分段，避免整本书作为一章导致排版缓慢
        val size = 8000
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) end = text.indexOf('\n', end).let { if (it == -1) text.length else it + 1 }
            chapters += ReaderChapter("第 ${chapters.size + 1} 部分", "", start, end)
            start = end
        }
        if (chapters.isEmpty()) chapters += ReaderChapter(file.nameWithoutExtension, "", 0, text.length)
    }
    return TxtReadSource(text, chapters)
}

/** 自动识别编码：BOM → UTF-8 → GBK（兼容常见的 ANSI/GBK 小说文件） */
private fun decodeText(bytes: ByteArray): String {
    fun b(i: Int) = bytes[i].toInt() and 0xFF
    if (bytes.size >= 2) {
        if (b(0) == 0xFF && b(1) == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE).normalizeNewlines()
        if (b(0) == 0xFE && b(1) == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).normalizeNewlines()
    }
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        .getOrElse {
            val gbk = runCatching { Charset.forName("GB18030") }.getOrNull() ?: Charsets.UTF_8
            String(bytes, gbk)
        }
        .removePrefix("﻿")
        .normalizeNewlines()
}

private fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

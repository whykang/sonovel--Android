package com.wang.sonovel.reader

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
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
 * 阅读器内容源：直接读取已导出的书籍文件（不额外占用存储），章节正文按需加载。
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

// ================================ EPUB ================================

private class EpubReadSource(
    private val zip: ZipFile,
    chapters: List<ReaderChapter>,
) : BookReadSource(chapters) {

    override fun paragraphs(index: Int): List<String> {
        val entry = zip.getEntry(chapters[index].locator) ?: return emptyList()
        val doc = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
        // 去掉章节标题，正文已按 <p> 分段
        doc.select("h1, h2, h3").remove()
        val list = doc.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        return list.ifEmpty { doc.body().text().lines().map { it.trim() }.filter { it.isNotEmpty() } }
    }

    override fun close() = runCatching { zip.close() }.let {}
}

private fun openEpub(file: File): BookReadSource {
    val zip = ZipFile(file)
    try {
        val opfPath = zip.getEntry("META-INF/container.xml")?.let { e ->
            zip.getInputStream(e).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
                .select("rootfile").attr("full-path").ifBlank { null }
        } ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf") }?.name
        ?: throw IOException("EPUB 缺少 content.opf")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = zip.getInputStream(zip.getEntry(opfPath)).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }

        // 优先用 toc.ncx 的目录（含章节名），否则退化为 spine 顺序
        val ncxHref = opf.select("manifest > item[media-type=application/x-dtbncx+xml]").attr("href")
        val chapters = mutableListOf<ReaderChapter>()
        if (ncxHref.isNotBlank()) {
            zip.getEntry(base + ncxHref)?.let { e ->
                val ncx = zip.getInputStream(e).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) }
                for (np in ncx.select("navPoint")) {
                    val src = np.selectFirst("content")?.attr("src")?.substringBefore('#') ?: continue
                    val title = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                    val entryName = base + src
                    if (zip.getEntry(entryName) != null) {
                        chapters += ReaderChapter(title.ifBlank { "第 ${chapters.size + 1} 章" }, entryName)
                    }
                }
            }
        }
        if (chapters.isEmpty()) {
            val manifest = opf.select("manifest > item").associate { it.attr("id") to it.attr("href") }
            for (ref in opf.select("spine > itemref")) {
                val href = manifest[ref.attr("idref")] ?: continue
                if (href.contains("cover") || href.contains("nav")) continue
                val entryName = base + href
                val entry = zip.getEntry(entryName) ?: continue
                val title = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
                    .let { d -> d.selectFirst("h1, h2")?.text() ?: d.title() }
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
        doc.select("h1, h2, h3, .bar, script, style").remove()
        return doc.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
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

private val CHAPTER_TITLE = Regex("^\\s*第?[零〇一二三四五六七八九十百千万\\d]{1,12}[章节節回卷话話][^\\n]{0,40}$")

private fun openTxt(file: File): BookReadSource {
    val text = decodeText(file.readBytes())
    val chapters = mutableListOf<ReaderChapter>()
    val starts = mutableListOf<Pair<Int, String>>() // 标题行结束位置 to 标题

    // 本应用导出的 TXT：正文段落以全角空格缩进，未缩进的非空行即章节名
    var i = 0
    var lineNo = 0
    while (i < text.length) {
        val nl = text.indexOf('\n', i).let { if (it == -1) text.length else it }
        val line = text.substring(i, nl)
        val trimmed = line.trim()
        val isHeader = lineNo < 3 && (trimmed.startsWith("书名：") || trimmed.startsWith("作者：") || trimmed.startsWith("简介："))
        if (trimmed.isNotEmpty() && !line.startsWith('　') && !line.startsWith("　") && !isHeader) {
            starts += (nl + 1) to trimmed
        }
        i = nl + 1
        lineNo++
    }
    // 若缩进规则不适用（如外部 TXT），退化为章节名正则
    val marks = if (starts.size >= 2) starts else buildList {
        var p = 0
        var ln = 0
        while (p < text.length) {
            val nl = text.indexOf('\n', p).let { if (it == -1) text.length else it }
            val line = text.substring(p, nl).trim()
            if (line.length <= 45 && CHAPTER_TITLE.matches(line)) add((nl + 1) to line)
            p = nl + 1
            ln++
        }
    }
    if (marks.isEmpty()) {
        // 整本作为一章
        return TxtReadSource(text, listOf(ReaderChapter(file.nameWithoutExtension, "", 0, text.length)))
    }
    for ((idx, m) in marks.withIndex()) {
        val end = if (idx + 1 < marks.size) marks[idx + 1].first - marks[idx + 1].second.length - 1 else text.length
        chapters += ReaderChapter(m.second, "", m.first, end.coerceAtLeast(m.first))
    }
    return TxtReadSource(text, chapters)
}

/** 按 UTF-8 解码，失败则按 GBK（兼容设置里导出的 GBK 编码 TXT） */
private fun decodeText(bytes: ByteArray): String {
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        .getOrElse {
            val gbk = runCatching { Charset.forName("GBK") }.getOrNull() ?: Charsets.UTF_8
            String(bytes, gbk)
        }
        .removePrefix("﻿")
}

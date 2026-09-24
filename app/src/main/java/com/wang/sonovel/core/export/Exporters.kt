package com.wang.sonovel.core.export

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.wang.sonovel.core.ChapterProcessor
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.ChapterContent
import com.wang.sonovel.data.ExportFormat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 章节按顺序逐个读取，避免整本书常驻内存 */
typealias ChapterSource = Sequence<ChapterContent>

object Exporters {

    fun export(
        format: ExportFormat,
        book: BookInfo,
        chapters: () -> ChapterSource,
        cover: ByteArray?,
        out: File,
        txtCharset: Charset,
    ) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        when (format) {
            ExportFormat.EPUB -> EpubWriter.write(book, chapters(), cover, tmp)
            ExportFormat.TXT -> TxtWriter.write(book, chapters(), tmp, txtCharset)
            ExportFormat.HTML -> HtmlZipWriter.write(book, chapters(), cover, tmp)
            ExportFormat.PDF -> PdfWriter.write(book, chapters(), tmp)
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** 段落 HTML 转为合法 XHTML 片段 */
    fun toXhtml(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return doc.body().html()
    }
}

// ================================ TXT ================================

object TxtWriter {
    fun write(book: BookInfo, chapters: ChapterSource, out: File, charset: Charset) {
        out.outputStream().buffered().writer(charset).use { w ->
            w.write("书名：${book.bookName}\n")
            w.write("作者：${book.author}\n")
            val intro = book.intro?.let { Jsoup.parse(it).text() }?.ifBlank { null } ?: "暂无"
            w.write("简介：$intro\n\n")
            val indent = "　　"
            for (c in chapters) {
                w.write(c.title)
                w.write("\n\n")
                for (p in ChapterProcessor.paragraphs(c.html)) {
                    w.write(indent); w.write(p); w.write("\n")
                }
                w.write("\n")
            }
        }
    }
}

// ================================ EPUB ================================

object EpubWriter {
    private const val CSS = """
body { margin: 0 4%; line-height: 1.75; }
h2 { font-size: 1.3em; text-align: center; margin: 1.2em 0 1em; }
p { text-indent: 2em; margin: 0.35em 0; letter-spacing: 0.02em; }
.cover { text-align: center; margin: 0; padding: 0; }
.cover img { max-width: 100%; max-height: 100%; }
.intro h1 { text-align: center; font-size: 1.5em; }
.intro .author { text-align: center; color: #666; text-indent: 0; }
"""

    fun write(book: BookInfo, chapters: ChapterSource, cover: ByteArray?, out: File) {
        val uid = "urn:uuid:" + UUID.nameUUIDFromBytes((book.url + book.bookName).toByteArray())
        val title = Exporters.escapeXml(book.bookName)
        val author = Exporters.escapeXml(book.author)
        val items = ArrayList<Pair<String, String>>() // id to title

        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            // mimetype 必须是第一个且不压缩
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            zip.setMethod(ZipOutputStream.DEFLATED)

            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }

            put(
                "META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
            )
            put("OEBPS/style.css", CSS)

            if (cover != null) {
                zip.putNextEntry(ZipEntry("OEBPS/cover.jpg")); zip.write(cover); zip.closeEntry()
                put("OEBPS/cover.xhtml", page("封面", """<div class="cover"><img src="cover.jpg" alt="封面"/></div>"""))
            }
            val introText = book.intro?.let { Jsoup.parse(it).text() }.orEmpty()
            put(
                "OEBPS/intro.xhtml", page(
                    book.bookName, """<div class="intro"><h1>$title</h1><p class="author">$author</p>""" +
                        (if (introText.isNotBlank()) "<p>${Exporters.escapeXml(introText)}</p>" else "") + "</div>"
                )
            )

            var index = 0
            for (c in chapters) {
                index++
                val id = "c%05d".format(index)
                items += id to c.title
                val body = "<h2>${Exporters.escapeXml(c.title)}</h2>\n" + Exporters.toXhtml(c.html)
                put("OEBPS/text/$id.xhtml", page(c.title, body, "../style.css"))
            }

            // nav.xhtml (EPUB3) + toc.ncx (EPUB2) 兼容新旧阅读器
            val navList = items.joinToString("\n") { (id, t) ->
                """      <li><a href="text/$id.xhtml">${Exporters.escapeXml(t)}</a></li>"""
            }
            put(
                "OEBPS/nav.xhtml", """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh" xml:lang="zh">
<head><title>目录</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
  <nav epub:type="toc" id="toc"><h2>目录</h2>
    <ol>
$navList
    </ol>
  </nav>
</body>
</html>"""
            )
            val navPoints = StringBuilder()
            items.forEachIndexed { i, (id, t) ->
                navPoints.append(
                    """    <navPoint id="np${i + 1}" playOrder="${i + 1}"><navLabel><text>${Exporters.escapeXml(t)}</text></navLabel><content src="text/$id.xhtml"/></navPoint>
"""
                )
            }
            put(
                "OEBPS/toc.ncx", """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$uid"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>$title</text></docTitle>
  <docAuthor><text>$author</text></docAuthor>
  <navMap>
$navPoints  </navMap>
</ncx>"""
            )

            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())
            val manifest = StringBuilder()
            val spine = StringBuilder()
            if (cover != null) {
                manifest.append("""    <item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>""").append('\n')
                manifest.append("""    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""    <itemref idref="cover" linear="yes"/>""").append('\n')
            }
            manifest.append("""    <item id="intro" href="intro.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
            spine.append("""    <itemref idref="intro"/>""").append('\n')
            for ((id, _) in items) {
                manifest.append("""    <item id="$id" href="text/$id.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""    <itemref idref="$id"/>""").append('\n')
            }
            val desc = introText.takeIf { it.isNotBlank() }?.let { "\n    <dc:description>${Exporters.escapeXml(it)}</dc:description>" }.orEmpty()
            put(
                "OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId" xml:lang="zh">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="BookId">$uid</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:creator id="creator">$author</dc:creator>
    <dc:language>zh</dc:language>
    <dc:publisher>so-novel</dc:publisher>
    <dc:rights>本电子书由 So Novel 制作生成，仅供交流使用，不得用于商业用途。</dc:rights>$desc
    <meta property="dcterms:modified">$date</meta>
    ${if (cover != null) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
$manifest  </manifest>
  <spine toc="ncx">
$spine  </spine>
  <guide>
    ${if (cover != null) "<reference type=\"cover\" title=\"封面\" href=\"cover.xhtml\"/>" else ""}
    <reference type="toc" title="目录" href="nav.xhtml"/>
  </guide>
</package>"""
            )
        }
    }

    private fun page(title: String, body: String, css: String = "style.css") = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="zh" xml:lang="zh">
<head>
  <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/>
  <title>${Exporters.escapeXml(title)}</title>
  <link rel="stylesheet" type="text/css" href="$css"/>
</head>
<body>
$body
</body>
</html>"""
}

// ================================ HTML (zip) ================================

object HtmlZipWriter {
    private const val STYLE = """
:root { color-scheme: light dark; --bg:#f7f5ef; --fg:#2b2b2b; --muted:#888; --accent:#1f6f5c; }
@media (prefers-color-scheme: dark) { :root { --bg:#141414; --fg:#b9b9b6; --muted:#777; --accent:#6fcfb4; } }
body { max-width: 760px; margin: 0 auto; padding: 24px 18px 60px; background: var(--bg); color: var(--fg);
  font-family: -apple-system, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif; }
h1 { font-size: 1.5em; margin: 1em 0; }
p { text-indent: 2em; line-height: 1.9; font-size: 1.12em; margin: 0.8em 0; letter-spacing: 0.04em; }
a { color: var(--accent); text-decoration: none; }
.bar { display: flex; justify-content: space-between; gap: 12px; margin-top: 48px; }
.bar a { flex: 1; text-align: center; padding: 12px; border: 1px solid var(--accent); border-radius: 10px; }
.bar a.disabled { opacity: .35; pointer-events: none; }
ol { padding-left: 1.6em; line-height: 2.2; }
.meta { color: var(--muted); }
img.cover { max-width: 180px; border-radius: 8px; }
"""

    fun write(book: BookInfo, chapters: ChapterSource, cover: ByteArray?, out: File) {
        val list = chapters.toList().let { it } // 需要知道上一章/下一章
        val width = maxOf(4, list.size.toString().length)
        fun fileOf(i: Int) = "%0${width}d.html".format(i + 1)
        val dir = Exporters.escapeXml(book.bookName)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            if (cover != null) {
                zip.putNextEntry(ZipEntry("${book.bookName}/cover.jpg")); zip.write(cover); zip.closeEntry()
            }
            val intro = book.intro?.let { Jsoup.parse(it).text() }.orEmpty()
            val toc = list.mapIndexed { i, c -> "<li><a href=\"${fileOf(i)}\">${Exporters.escapeXml(c.title)}</a></li>" }
                .joinToString("\n")
            put(
                "${book.bookName}/index.html", html(
                    book.bookName, """
${if (cover != null) "<p style=\"text-indent:0\"><img class=\"cover\" src=\"cover.jpg\" alt=\"\"/></p>" else ""}
<h1>$dir</h1>
<p class="meta" style="text-indent:0">作者：${Exporters.escapeXml(book.author)}</p>
${if (intro.isNotBlank()) "<p>${Exporters.escapeXml(intro)}</p>" else ""}
<h2>目录</h2>
<ol>
$toc
</ol>"""
                )
            )
            list.forEachIndexed { i, c ->
                val prev = if (i > 0) "<a href=\"${fileOf(i - 1)}\">上一章</a>" else "<a class=\"disabled\">上一章</a>"
                val next = if (i < list.size - 1) "<a href=\"${fileOf(i + 1)}\">下一章</a>" else "<a class=\"disabled\">下一章</a>"
                put(
                    "${book.bookName}/${fileOf(i)}", html(
                        c.title, """<h1>${Exporters.escapeXml(c.title)}</h1>
${c.html}
<div class="bar">$prev<a href="index.html">目录</a>$next</div>
<script>
document.addEventListener('keyup', function (e) {
  var a = document.querySelectorAll('.bar a');
  if (e.key === 'ArrowLeft' && a[0].href) location.href = a[0].href;
  if (e.key === 'ArrowRight' && a[2].href) location.href = a[2].href;
});
</script>"""
                    )
                )
            }
        }
    }

    private fun html(title: String, body: String) = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<title>${Exporters.escapeXml(title)}</title>
<style>$STYLE</style>
</head>
<body>
$body
</body>
</html>"""
}

// ================================ PDF ================================

/**
 * 使用 Android 原生 PdfDocument 排版（系统字体自带中文，Skia 自动嵌入字形子集）
 * 页面尺寸与桌面版一致：7.36 × 9.76 英寸
 */
object PdfWriter {
    private const val PAGE_W = 530  // 7.36in * 72
    private const val PAGE_H = 703  // 9.76in * 72
    private const val MARGIN_X = 44f
    private const val MARGIN_Y = 52f

    fun write(book: BookInfo, chapters: ChapterSource, out: File) {
        val doc = PdfDocument()
        val bodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 13.5f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val titlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 19f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val footPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f }
        val contentW = (PAGE_W - MARGIN_X * 2).toInt()
        val bottom = PAGE_H - MARGIN_Y

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finish() {
            page?.let {
                val t = "${book.bookName} · $pageNo"
                it.canvas.drawText(t, (PAGE_W - footPaint.measureText(t)) / 2, PAGE_H - 22f, footPaint)
                doc.finishPage(it)
            }
            page = null
        }

        fun newPage() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN_Y
        }

        /** 绘制一段文字，必要时按行跨页 */
        fun drawBlock(text: String, paint: TextPaint, lineMult: Float, spaceAfter: Float, center: Boolean = false) {
            val layout = staticLayout(text, paint, contentW, lineMult, center)
            for (line in 0 until layout.lineCount) {
                val h = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (page == null || y + h > bottom) newPage()
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate(MARGIN_X, y - layout.getLineTop(line))
                canvas.clipRect(0f, layout.getLineTop(line).toFloat(), contentW.toFloat(), layout.getLineBottom(line).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += h
            }
            y += spaceAfter
        }

        // 书名页
        newPage()
        y = PAGE_H * 0.3f
        drawBlock(book.bookName, TextPaint(titlePaint).apply { textSize = 28f }, 1.2f, 18f, center = true)
        drawBlock(book.author, TextPaint(bodyPaint).apply { color = Color.DKGRAY }, 1.2f, 30f, center = true)
        book.intro?.let { Jsoup.parse(it).text() }?.takeIf { it.isNotBlank() }?.let {
            drawBlock(it, TextPaint(bodyPaint).apply { textSize = 11f; color = Color.DKGRAY }, 1.5f, 0f)
        }

        for (c in chapters) {
            newPage() // 每章从新页开始
            y += 12f
            drawBlock(c.title, titlePaint, 1.25f, 16f)
            for (p in ChapterProcessor.paragraphs(c.html)) {
                drawBlock("　　" + p, bodyPaint, 1.6f, 5f)
            }
        }
        finish()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    @Suppress("DEPRECATION")
    private fun staticLayout(text: String, paint: TextPaint, width: Int, mult: Float, center: Boolean): StaticLayout {
        val align = if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(align)
                .setLineSpacing(0f, mult)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, paint, width, align, mult, 0f, false)
        }
    }
}

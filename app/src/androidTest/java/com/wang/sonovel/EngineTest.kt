package com.wang.sonovel

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wang.sonovel.core.BookParser
import com.wang.sonovel.core.ChapterParser
import com.wang.sonovel.core.ChapterProcessor
import com.wang.sonovel.core.ChineseConverter
import com.wang.sonovel.core.Extractor
import com.wang.sonovel.core.JsEngine
import com.wang.sonovel.core.SearchParser
import com.wang.sonovel.core.SearchRanker
import com.wang.sonovel.core.SourceContext
import com.wang.sonovel.core.TocParser
import com.wang.sonovel.core.export.Exporters
import com.wang.sonovel.data.ExportFormat
import com.wang.sonovel.data.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * 在真机上验证核心引擎：JS 引擎、简繁转换、DSL 解析，以及针对真实书源的 搜索 → 详情 → 目录 → 正文 → 导出
 */
@RunWith(AndroidJUnit4::class)
class EngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = ctx.graph

    private fun log(msg: String) = Log.i("EngineTest", msg)

    @Test
    fun jsEngineSupportsModernSyntax() {
        // 规则中用到的语法：展开运算符、matchAll、箭头函数、replaceAll、const/let、for...of
        val out = JsEngine.call(
            "const ds=[...r.matchAll(/<dd data-id=\"(\\d+)\">(.*?)<\\/dd>/g)].map(m=>({id:Number(m[1]),c:m[2]}));" +
                "ds.sort((a,b)=>a.id-b.id);let s='';for(const d of ds){s+=d.c};r=s.replaceAll('x','y');",
            "<dd data-id=\"2\">B</dd><dd data-id=\"1\">Ax</dd>",
        )
        assertEquals("AyB", out)
        // search.url 形式：直接 return 字符串
        assertEquals("https://a.com/?k=中文", JsEngine.call("return 'https://a.com/?k='+r", "中文"))
        // 顶点小说 toc.list 规则的真实 JS
        val rule = graph.rules.allRules.first { it.name == "顶点小说" }
        val code = rule.toc!!.list!!.substringAfter("@js:")
        val html = "<style>.section-list.ycxsid>li:nth-child(1){display:none}</style>" +
            "<ul class=\"section-list ycxsid\"><li>a</li><li>b</li><li>c</li></ul>"
        val res = JsEngine.call(code, html)
        assertTrue(res, res.contains("<li>b</li>") && !res.contains("<li>a</li>"))
    }

    @Test
    fun dslAndConverter() {
        val p = Extractor.parse("//*[@id=\"readbg\"]/script[4]@js:r=r.match(/x/)[1];@java:base64.decode()")
        assertEquals("//*[@id=\"readbg\"]/script[4]", p.selector)
        assertEquals(listOf("js", "java"), p.steps.map { it.lang })
        assertEquals("#list a", Extractor.selectorOf("#list a@href"))
        assertEquals("軟體", ChineseConverter.s2tw("软件"))
        assertEquals("头发", ChineseConverter.t2s("頭髮"))
        assertEquals("後來", ChineseConverter.s2t("后来"))
        assertTrue(SearchRanker.similar("诡秘之主", "诡秘之主") == 1.0)
    }

    @Test
    fun diagnoseShortChapter() = runBlocking {
        val rule = graph.rules.allRules.first { it.name == "书海阁小说网" }
        val c = SourceContext(rule, graph.settings.current)
        val hit = SearchParser(c).search("海贼王之弑神").firstOrNull { it.bookName == "海贼王之弑神" } ?: return@runBlocking
        val toc = TocParser(c).parseAll(hit.url)
        for (ch in toc.filter { it.order in setOf(47, 101) }) {
            val raw = ChapterParser(c).fetchContent(ch.url, 0)
            log("DIAG ${ch.order} ${ch.title} ${ch.url} rawLen=${raw.length}")
            raw.chunked(900).take(4).forEach { log("DIAG raw: $it") }
            val p = ChapterProcessor(c).process(ch.order, ch.title, raw)
            log("DIAG processed: ${p.html.take(600)}")
        }
    }

    @Test
    fun endToEndRealSources() = runBlocking {
        val s = graph.settings.current.copy(fetchBetterCover = false)
        val kw = "诡秘之主"
        val rules = graph.rules.allRules.filter { it.file == "main.json" && it.searchable }
        val results: List<Pair<String, List<SearchResult>>> = rules.map { r ->
            async(Dispatchers.IO) {
                val res = withTimeoutOrNull(45_000) {
                    runCatching { SearchParser(SourceContext(r, s)).search(kw) }
                        .onFailure { log("搜索失败 ${r.name}: ${it.message}") }
                        .getOrDefault(emptyList())
                } ?: emptyList<SearchResult>().also { log("搜索超时 ${r.name}") }
                log("搜索 ${r.name}: ${res.size} 条  ${res.firstOrNull()?.let { it.bookName + "/" + it.author + " " + it.url } ?: ""}")
                r.name!! to res
            }
        }.awaitAll()
        val ok = results.count { it.second.isNotEmpty() }
        log("可用书源 $ok / ${rules.size}")
        assertTrue("没有任何书源返回搜索结果", ok > 0)

        // 逐个尝试可用书源，直到完整走通一次
        var passed = false
        val errors = StringBuilder()
        for ((name, list) in results.filter { it.second.isNotEmpty() }) {
            val hit = SearchRanker.filterAndSort(list, kw, true).firstOrNull() ?: continue
            val rule = graph.rules.byKey(hit.sourceKey)!!
            val c = SourceContext(rule, s)
            try {
                val book = BookParser(c).parse(hit.url)
                log("详情 $name: ${book.bookName} / ${book.author} cover=${book.coverUrl} intro=${book.intro?.take(30)}")
                val toc = withTimeoutOrNull(90_000) { TocParser(c).parseAll(hit.url) } ?: error("目录超时")
                log("目录 $name: ${toc.size} 章, 首章 ${toc.firstOrNull()}")
                assertTrue("目录为空", toc.size > 10)
                val chapters = toc.take(3).map { ch ->
                    val raw = ChapterParser(c).fetchContent(ch.url, 300)
                    ChapterProcessor(c).process(ch.order, ch.title, raw)
                }
                val paras = ChapterProcessor.paragraphs(chapters.first().html)
                log("正文 $name: ${chapters.first().title} 段落 ${paras.size}: ${paras.take(2)}")
                assertTrue("正文段落过少", paras.size >= 5)

                val dir = File(ctx.cacheDir, "test-export").apply { deleteRecursively(); mkdirs() }
                for (f in ExportFormat.entries) {
                    val out = File(dir, "book.${f.ext}")
                    Exporters.export(f, book, { chapters.asSequence() }, null, out, Charsets.UTF_8)
                    log("导出 ${f.label}: ${out.length()} bytes")
                    assertTrue(out.length() > 500)
                }
                ZipFile(File(dir, "book.epub")).use { z ->
                    assertEquals("mimetype", z.entries().nextElement().name)
                    assertTrue(z.getEntry("OEBPS/content.opf") != null)
                }
                assertTrue(File(dir, "book.pdf").readBytes().copyOfRange(0, 4).decodeToString() == "%PDF")
                assertTrue(File(dir, "book.txt").readText().contains(chapters.first().title))
                passed = true
                break
            } catch (e: Throwable) {
                log("书源 $name 流程失败: ${e.message}")
                errors.append("$name: ${e.message}\n")
            }
        }
        assertTrue("所有书源完整流程都失败:\n$errors", passed)
    }
}

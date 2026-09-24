package com.wang.sonovel.core

import android.graphics.BitmapFactory
import com.wang.sonovel.data.AppSettings
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.Rule
import com.wang.sonovel.data.SearchResult
import com.wang.sonovel.data.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

// ================================ 封面 ================================

object CoverFetcher {
    const val DEFAULT_COVER = "https://bookcover.yuewen.com/qdbimg/no-cover"
    private const val TIMEOUT = 5

    /** 从起点 / 纵横 / 七猫并行获取封面，取分辨率最大者 */
    suspend fun best(book: BookInfo, s: AppSettings): String? = coroutineScope {
        val fallback = book.coverUrl?.takeIf { it.isNotBlank() }
        val candidates = listOf(
            async(Dispatchers.IO) { runCatching { qidian(book, s) }.getOrNull() },
            async(Dispatchers.IO) { runCatching { zongheng(book, s) }.getOrNull() },
            async(Dispatchers.IO) { runCatching { qimao(book, s) }.getOrNull() },
        ).awaitAll().filterNotNull().filter { it.startsWith("http") }
        if (candidates.isEmpty()) return@coroutineScope fallback
        val sized = candidates.map { url ->
            async(Dispatchers.IO) { url to (runCatching { pixels(download(url, s)) }.getOrDefault(0)) }
        }.awaitAll().filter { it.second > 0 }
        sized.maxByOrNull { it.second }?.first ?: fallback
    }

    fun download(url: String, s: AppSettings): ByteArray {
        val page = Http.get(Http.client(s), url, 20)
        return page.bytes
    }

    private fun pixels(bytes: ByteArray): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth * o.outHeight else 0
    }

    private fun match(book: BookInfo, name: String?, author: String?): Boolean {
        fun norm(x: String?) = ChineseConverter.t2s(Jsoup.parse(x.orEmpty()).text().trim())
        return norm(book.bookName) == norm(name) && norm(book.author) == norm(author)
    }

    private fun qidian(book: BookInfo, s: AppSettings): String? {
        if (s.qidianCookie.isBlank()) return null
        val url = "https://www.qidian.com/so/${Http.encode(book.bookName)}.html"
        val page = Http.get(Http.client(s), url, TIMEOUT, mapOf("Cookie" to s.qidianCookie))
        val doc = Jsoup.parse(page.text, url)
        for (e in doc.select(".res-book-item")) {
            val name = e.select(".book-mid-info > .book-info-title > a").text()
            val author = e.select(".book-mid-info > .author > .name").text()
                .ifBlank { e.select(".book-mid-info > .author > i").text() }
            if (match(book, name, author)) {
                var cover = e.select(".book-img-box > a > img").attr("src")
                if (cover.startsWith("//")) cover = "https:$cover"
                return cover.replace(Regex("/150(\\.webp)?"), "")
            }
        }
        return null
    }

    private fun zongheng(book: BookInfo, s: AppSettings): String? {
        val url = "https://search.zongheng.com/search/book?keyword=${Http.encode(book.bookName)}&pageNo=1&pageNum=20&isFromHuayu=0"
        val json = JSONObject(Http.get(Http.client(s), url, TIMEOUT).text)
        val list = json.optJSONObject("data")?.optJSONObject("datas")?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (match(book, o.optString("name"), o.optString("authorName"))) {
                return "https://static.zongheng.com/upload" + o.optString("coverUrl")
            }
        }
        return null
    }

    private fun qimao(book: BookInfo, s: AppSettings): String? {
        val url = "https://www.qimao.com/qimaoapi/api/search/result?keyword=${Http.encode(book.bookName)}&count=0&page=1&page_size=15"
        val json = JSONObject(Http.get(Http.client(s), url, TIMEOUT).text)
        val list = json.optJSONObject("data")?.optJSONArray("search_list") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (match(book, o.optString("title"), o.optString("author"))) return o.optString("image_link")
        }
        return null
    }
}

// ================================ 搜索结果排序 ================================

object SearchRanker {

    /** hutool TextSimilarity.similar：去符号后的最长公共子序列 / 较长串长度 */
    fun similar(a: String?, b: String?): Double {
        val x = removeSign(a.orEmpty())
        val y = removeSign(b.orEmpty())
        val (s1, s2) = if (x.length >= y.length) x to y else y to x
        val max = maxOf(s1.length, s2.length)
        if (max == 0) return 1.0
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 1..s1.length) for (j in 1..s2.length) {
            dp[i][j] = if (s1[i - 1] == s2[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
        return dp[s1.length][s2.length].toDouble() / max
    }

    private fun removeSign(s: String) = buildString {
        // 汉字属于 Lo 类别，isLetterOrDigit 已覆盖
        for (c in s) if (c.isLetterOrDigit()) append(c)
    }

    private fun weight(s: Double, short: Boolean, long: Boolean): Double = when {
        short -> when {
            s == 1.0 -> 12.0
            s >= 0.8 -> s * s * s * 8
            s >= 0.7 -> s * 5
            else -> 0.0
        }
        long -> when {
            s == 1.0 -> 10.0
            s >= 0.85 -> s * s * s * 8
            s >= 0.7 -> s * s * 5
            s >= 0.5 -> s * 3
            else -> s * 1.2
        }
        else -> when {
            s == 1.0 -> 10.0
            s >= 0.85 -> s * s * s * 8
            s >= 0.7 -> s * s * 5
            s >= 0.5 -> s * 3
            else -> 0.0
        }
    }

    /** 判断按书名还是作者搜索，过滤低相似度结果并排序 */
    fun filterAndSort(list: List<SearchResult>, kw: String, filter: Boolean): List<SearchResult> {
        if (list.isEmpty()) return list
        val bookSim = list.associateWith { similar(kw, it.bookName) }
        val authorSim = list.associateWith { similar(kw, it.author) }
        val short = kw.length <= 4
        val long = kw.length >= 10
        val byAuthor = bookSim.values.sumOf { weight(it, short, long) } < authorSim.values.sumOf { weight(it, short, long) }
        val sim = if (byAuthor) authorSim else bookSim
        val cmp = Comparator<SearchResult> { a, b ->
            val d = sim.getValue(b).compareTo(sim.getValue(a))
            if (d != 0) d
            else if (byAuthor) a.bookName.compareTo(b.bookName)
            else a.author.orEmpty().compareTo(b.author.orEmpty())
        }
        val filtered = if (filter) list.filter { sim.getValue(it) > 0.25 }.sortedWith(cmp) else emptyList()
        return filtered.ifEmpty { list.filter { sim.getValue(it) > 0 }.sortedWith(cmp) }
    }
}

// ================================ 其他服务 ================================

object Misc {
    /** 百度搜索建议 */
    suspend fun suggestions(kw: String, s: AppSettings): List<String> = withContext(Dispatchers.IO) {
        if (kw.isBlank()) return@withContext emptyList()
        runCatching {
            val page = Http.get(Http.client(s), "https://www.baidu.com/sugrec?prod=pc&wd=${Http.encode(kw)}", 5)
            val arr = JSONObject(page.text).optJSONArray("g") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("q")?.takeIf { q -> q.isNotBlank() } }.take(8)
        }.getOrDefault(emptyList())
    }

    /** 书源连通性检测（HEAD 请求，3 秒超时） */
    suspend fun checkSource(rule: Rule, s: AppSettings): SourceStatus = withContext(Dispatchers.IO) {
        val url = rule.url ?: return@withContext SourceStatus(-1, -1)
        runCatching {
            val client = Http.client(s, rule.ignoreSsl)
            val call = client.newCall(Request.Builder().url(url).head().header("User-Agent", RandomUA.generate()).build())
            call.timeout().timeout(4, TimeUnit.SECONDS)
            val start = System.currentTimeMillis()
            call.execute().use { SourceStatus((System.currentTimeMillis() - start).toInt(), it.code) }
        }.getOrElse { SourceStatus(-1, -1) }
    }
}

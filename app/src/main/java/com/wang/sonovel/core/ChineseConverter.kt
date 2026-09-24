package com.wang.sonovel.core

import android.content.Context
import com.wang.sonovel.data.BookInfo
import com.wang.sonovel.data.LangType
import com.wang.sonovel.data.SearchResult

/**
 * 简繁转换，基于 OpenCC 词典（assets/opencc）的正向最大匹配，适用于所有 Android 版本。
 * 对应桌面版基于 HanLP 的 ChineseConverter：t2s / s2t / s2tw / t2tw。
 */
object ChineseConverter {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private class Dict(val map: HashMap<String, String>, val maxLen: Int)

    private val cache = HashMap<String, Dict>()

    private fun dict(vararg names: String): Dict = synchronized(cache) {
        val key = names.joinToString("+")
        cache.getOrPut(key) {
            val map = HashMap<String, String>(64 * 1024)
            var max = 1
            for (name in names) {
                appContext.assets.open("opencc/$name.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val k = line.substring(0, tab)
                            val v = line.substring(tab + 1).substringBefore(' ')
                            // 先加载的（词组）优先，不覆盖
                            if (!map.containsKey(k)) {
                                map[k] = v
                                if (k.length > max) max = k.length
                            }
                        }
                    }
                }
            }
            Dict(map, max)
        }
    }

    private fun convertWith(text: String, d: Dict): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            var matched = false
            var len = minOf(d.maxLen, n - i)
            while (len > 0) {
                val v = d.map[text.substring(i, i + len)]
                if (v != null) {
                    sb.append(v); i += len; matched = true
                    break
                }
                len--
            }
            if (!matched) {
                sb.append(text[i]); i++
            }
        }
        return sb.toString()
    }

    fun s2t(text: String) = convertWith(text, dict("STPhrases", "STCharacters"))
    fun t2s(text: String) = convertWith(text, dict("TSPhrases", "TSCharacters"))
    fun t2tw(text: String) = convertWith(text, dict("TWPhrases", "TWVariants"))
    fun s2tw(text: String) = t2tw(s2t(text))

    private fun function(source: String?, target: String): ((String) -> String)? {
        val src = source ?: return null
        if (src == target) return null
        return when ("$src>$target") {
            "${LangType.ZH_TW}>${LangType.ZH_CN}", "${LangType.ZH_HANT}>${LangType.ZH_CN}" -> ::t2s
            "${LangType.ZH_CN}>${LangType.ZH_TW}" -> ::s2tw
            "${LangType.ZH_CN}>${LangType.ZH_HANT}" -> ::s2t
            "${LangType.ZH_HANT}>${LangType.ZH_TW}" -> ::t2tw
            else -> null
        }
    }

    fun converter(source: String?, target: String): (String) -> String = function(source, target) ?: { it }

    fun convert(sr: SearchResult, source: String?, target: String): SearchResult {
        val f = function(source, target) ?: return sr
        return sr.copy(
            bookName = f(sr.bookName),
            author = sr.author?.let(f),
            category = sr.category?.let(f),
            latestChapter = sr.latestChapter?.let(f),
            lastUpdateTime = sr.lastUpdateTime?.let(f),
            status = sr.status?.let(f),
            wordCount = sr.wordCount?.let(f),
        )
    }

    fun convert(b: BookInfo, source: String?, target: String): BookInfo {
        val f = function(source, target) ?: return b
        return b.copy(
            bookName = f(b.bookName),
            author = f(b.author),
            intro = b.intro?.let(f),
            category = b.category?.let(f),
            latestChapter = b.latestChapter?.let(f),
            lastUpdateTime = b.lastUpdateTime?.let(f),
            status = b.status?.let(f),
        )
    }
}

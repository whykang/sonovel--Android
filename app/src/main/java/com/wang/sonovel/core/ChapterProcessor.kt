package com.wang.sonovel.core

import com.wang.sonovel.data.ChapterContent
import com.wang.sonovel.data.Rule
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * 章节正文处理：过滤（不可见字符 / 实体 / 广告 / 重复标题 / 空标签）→ 排版为 <p> 段落 → 简繁转换
 */
class ChapterProcessor(private val ctx: SourceContext) {
    private val r: Rule.Chapter = ctx.rule.chapter ?: Rule.Chapter()
    private val convert = ChineseConverter.converter(ctx.sourceLang, ctx.targetLang)
    private val filterTxtRegex = r.filterTxt?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
    private val paragraphRegex = runCatching { Regex(r.paragraphTag?.takeIf { it.isNotBlank() } ?: "<br\\s*/?>") }
        .getOrElse { Regex("<br\\s*/?>") }

    fun process(order: Int, rawTitle: String, rawContent: String): ChapterContent {
        var title = rawTitle.trim()
        var content = rawContent

        // 1. 不可见字符（导致乱码的控制符、私有区字符）
        content = content.replace(INVISIBLE, "")
        // 2. HTML 实体（可能导致部分阅读器报错）
        content = content.replace(ENTITY, "")
        // 3. 广告
        filterTxtRegex?.let { content = content.replace(it, "") }
        if (!r.filterTag.isNullOrBlank()) content = removeTags(content, r.filterTag!!)
        // 4. 正文开头的重复标题
        if (title.isNotEmpty()) {
            val re = Regex("^(\\s|<[^>]+>)*(" + Regex.escape(title) + "|" + Regex.escape(cleanBlank(title)) + ")")
            content = content.replaceFirst(re, "$1")
            TITLE_NUMBER.find(title)?.let { m -> title = "第${m.groupValues[1]}章 ${m.groupValues[2]}" }
        }
        // 5. 空标签
        content = content.replace(EMPTY_TAG, "")

        // 排版
        content = format(content)
        return ChapterContent(order, convert(title), convert(content))
    }

    private fun format(html: String): String {
        val content = clearAllAttributes(html)
        if (r.paragraphTagClosed) {
            // 非 <p> 闭合标签替换为 <p>
            val replaced = content.replace(CLOSED_TAG, "<p>$2</p>")
            return if (replaced.contains("<p>")) replaced else "<p>$replaced</p>"
        }
        val sb = StringBuilder()
        for (line in content.split(paragraphRegex)) {
            val text = line.replace(P_TAGS, "")
            if (text.isNotBlank()) sb.append("<p>").append(text).append("</p>")
        }
        return sb.toString()
    }

    companion object {
        private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Zl}\\p{Zp}\\u200B\\uFEFF]")
        private val ENTITY = Regex("&(#\\d{1,6}|#x[0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,9});")
        private val TITLE_NUMBER = Regex("^(\\d+)\\s*\\.\\s*(.+)$")
        private val EMPTY_TAG = Regex("<(\\w+)([^>]*)>\\s*</\\1>")
        private val CLOSED_TAG = Regex("<(?!p\\b)([^>\\s]+)>(.*?)</\\1>")
        private val P_TAGS = Regex("</?p>")
        private val P_CONTENT = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        private val TAG = Regex("<[^>]+>")

        fun clearAllAttributes(html: String): String {
            val body = Jsoup.parse(html).body()
            for (el in body.select("*")) el.clearAttributes()
            return cleanBlank(body.html())
        }

        fun removeTags(html: String, cssQuery: String): String {
            if (html.isBlank()) return html
            return runCatching {
                val doc = Jsoup.parseBodyFragment(html)
                doc.select(cssQuery).remove()
                doc.body().html()
            }.getOrDefault(html)
        }

        /** 从段落 HTML 取出纯文本段落 */
        fun paragraphs(html: String): List<String> {
            val list = P_CONTENT.findAll(html).map { m ->
                Parser.unescapeEntities(m.groupValues[1].replace(TAG, ""), false).trim()
            }.filter { it.isNotEmpty() }.toList()
            if (list.isNotEmpty()) return list
            val plain = Parser.unescapeEntities(html.replace(TAG, "\n"), false)
            return plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

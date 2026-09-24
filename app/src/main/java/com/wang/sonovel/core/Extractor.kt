package com.wang.sonovel.core

import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

enum class ContentType(val attr: String) {
    TEXT("text"), HTML("html"), ATTR_HREF("href"), ATTR_SRC("src"), ATTR_CONTENT("content"), ATTR_VALUE("value")
}

/**
 * 规则表达式：`选择器[@href|@src][@js:代码][@java:代码]...`
 * 选择器支持 CSS Selector 与 XPath（以 / 或 ( 开头）。
 */
object Extractor {

    private val DSL_MARKER = Regex("@(js|java):")
    private val ATTR_SUFFIX = Regex("@(href|src)\\s*$")

    data class Step(val lang: String, val code: String)
    data class Parsed(val selector: String, val steps: List<Step>)

    fun parse(query: String): Parsed {
        val matches = DSL_MARKER.findAll(query).toList()
        if (matches.isEmpty()) return Parsed(query.trim(), emptyList())
        val init = query.substring(0, matches.first().range.first)
        val steps = matches.mapIndexed { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else query.length
            Step(m.groupValues[1], query.substring(m.range.last + 1, end).trim())
        }
        return Parsed(init.trim(), steps)
    }

    /** 仅选择器部分（去掉 @href/@src 与 DSL） */
    fun selectorOf(query: String): String = parse(query).selector.replace(ATTR_SUFFIX, "").trim()

    /** 执行 DSL 步骤 */
    fun runDsl(query: String?, input: String?): String {
        var result = input ?: ""
        if (query.isNullOrBlank()) return result
        for (step in parse(query).steps) {
            result = when (step.lang) {
                "js" -> JsEngine.call(step.code, result)
                "java" -> javaStep(step.code, result)
                else -> result
            }
        }
        return result
    }

    private val REPLACE_RE = Regex("""string\.replace\('([^']*)','([^']*)'\)""")

    /** 桌面版 JavaExecutor 支持的内置操作 */
    private fun javaStep(code: String, input: String): String {
        if (code == "base64.decode()") {
            return input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("") { line ->
                    runCatching { String(Base64.decode(line, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(line)
                }
        }
        REPLACE_RE.matchEntire(code)?.let { m ->
            return input.replace(Regex(m.groupValues[1]), m.groupValues[2])
        }
        return input
    }

    fun select(el: Element, query: String?): Elements {
        if (query.isNullOrBlank()) return Elements()
        val selector = selectorOf(query)
        if (selector.isEmpty()) return Elements()
        return runCatching {
            if (selector.startsWith("/") || selector.startsWith("(")) el.selectXpath(selector) else el.select(selector)
        }.getOrElse { Elements() }
    }

    private fun contentTypeOf(query: String): ContentType {
        val sel = parse(query).selector
        return when {
            sel.contains("@href") -> ContentType.ATTR_HREF
            sel.contains("@src") -> ContentType.ATTR_SRC
            sel.startsWith("meta[") -> ContentType.ATTR_CONTENT
            else -> ContentType.TEXT
        }
    }

    /** 查询元素并提取内容，再交给 DSL 二次处理 */
    fun extract(el: Element?, query: String?, type: ContentType? = null): String? {
        if (el == null || query.isNullOrBlank()) return null
        val ct = type ?: contentTypeOf(query)
        val els = select(el, query)
        if (els.isEmpty()) return ""
        val raw = if (els.size == 1) content(els.first()!!, ct) else content(els, ct)
        return runDsl(query, raw)
    }

    /** 对已有元素取内容 + 可选 DSL */
    fun extractContent(el: Element, query: String?, type: ContentType): String = runDsl(query, content(el, type))

    private fun content(el: Element, t: ContentType): String = when (t) {
        ContentType.TEXT -> el.text()
        ContentType.HTML -> el.html()
        ContentType.ATTR_HREF, ContentType.ATTR_SRC -> el.absUrl(t.attr).ifEmpty { el.attr(t.attr) }
        ContentType.ATTR_CONTENT, ContentType.ATTR_VALUE -> el.attr(t.attr)
    }

    private fun content(els: Elements, t: ContentType): String = when (t) {
        ContentType.TEXT -> els.text()
        ContentType.HTML -> els.html()
        ContentType.ATTR_HREF, ContentType.ATTR_SRC ->
            els.firstOrNull { it.hasAttr(t.attr) }?.let { it.absUrl(t.attr).ifEmpty { it.attr(t.attr) } }.orEmpty()
        ContentType.ATTR_CONTENT, ContentType.ATTR_VALUE -> els.attr(t.attr)
    }
}

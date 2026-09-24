package com.wang.sonovel.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class RuleFile(
    val name: String,
    val builtIn: Boolean,
    val rules: List<Rule>,
    /** 覆盖了同名内置文件 */
    val overridesBuiltIn: Boolean = false,
)

/**
 * 书源规则仓库：内置规则位于 assets/rules，用户导入的规则位于 files/rules（同名覆盖内置）。
 */
class RuleRepository(private val context: Context, private val settings: SettingsRepository) {

    private val gson = GsonBuilder().create()
    private val userDir = File(context.filesDir, "rules").apply { mkdirs() }
    private val _files = MutableStateFlow<List<RuleFile>>(emptyList())
    val files: StateFlow<List<RuleFile>> = _files.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        val builtIn = context.assets.list("rules").orEmpty().filter { it.endsWith(".json") }.sorted()
        val user = userDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }.sorted()
        val result = mutableListOf<RuleFile>()
        // main.json 始终排在第一位
        val order = (builtIn + user).distinct().sortedWith(compareBy({ it != "main.json" }, { it !in builtIn }, { it }))
        for (name in order) {
            val userFile = File(userDir, name)
            val text = runCatching {
                if (userFile.exists()) userFile.readText()
                else context.assets.open("rules/$name").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val rules = runCatching { parse(text, name) }.getOrElse { emptyList() }
            result += RuleFile(
                name = name,
                builtIn = name in builtIn,
                rules = rules,
                overridesBuiltIn = name in builtIn && userFile.exists(),
            )
        }
        _files.value = result
    }

    /** 解析规则 JSON 数组并填充默认值 */
    fun parse(text: String, fileName: String): List<Rule> {
        // 兼容 json5 风格的注释
        val cleaned = stripComments(text)
        val type = object : TypeToken<List<Rule>>() {}.type
        val list: List<Rule> = gson.fromJson(cleaned, type) ?: emptyList()
        return list.filter { !it.url.isNullOrBlank() }.mapIndexed { i, r ->
            r.id = i + 1
            r.file = fileName
            applyDefaults(r)
        }
    }

    fun rawJson(rule: Rule): String {
        val file = File(userDir, rule.file)
        val text = if (file.exists()) file.readText()
        else context.assets.open("rules/${rule.file}").bufferedReader().use { it.readText() }
        return runCatching {
            val arr = JsonParser.parseString(stripComments(text)).asJsonArray
                .filter { it.asJsonObject.get("url")?.asString?.isNotBlank() == true }
            GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(arr[rule.id - 1])
        }.getOrDefault("")
    }

    val activeFile: RuleFile?
        get() = _files.value.firstOrNull { it.name == settings.current.activeRules } ?: _files.value.firstOrNull()

    val activeRules: List<Rule> get() = activeFile?.rules.orEmpty()

    val allRules: List<Rule> get() = _files.value.flatMap { it.rules }

    fun isEnabled(rule: Rule): Boolean =
        !rule.disabled && disabledKey(rule) !in settings.current.disabledSources

    fun disabledKey(rule: Rule) = "${rule.file}#${rule.url}"

    fun setEnabled(rule: Rule, enabled: Boolean) = settings.update {
        val key = disabledKey(rule)
        it.copy(disabledSources = if (enabled) it.disabledSources - key else it.disabledSources + key)
    }

    /** 可参与聚合搜索的书源 */
    fun searchableRules(): List<Rule> = activeRules.filter { it.searchable && isEnabled(it) }

    fun byKey(key: String): Rule? = allRules.firstOrNull { it.key == key }

    /** 根据书籍链接匹配书源：优先当前激活文件，其次全部文件 */
    fun matchByUrl(bookUrl: String): Rule? {
        val url = bookUrl.trim()
        fun match(r: Rule): Boolean {
            val base = r.url?.trim()?.trimEnd('/') ?: return false
            if (url.startsWith(base)) return true
            val host = hostOf(base) ?: return false
            return hostOf(url)?.removePrefix("www.") == host.removePrefix("www.")
        }
        return activeRules.firstOrNull(::match) ?: allRules.firstOrNull(::match)
    }

    /** 导入规则文件，返回规则数量 */
    fun import(fileName: String, text: String): Int {
        val name = fileName.substringAfterLast('/').let { if (it.endsWith(".json")) it else "$it.json" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val rules = parse(text, name)
        require(rules.isNotEmpty()) { "未解析到有效书源（需为 JSON 数组，且每个书源包含 url）" }
        File(userDir, name).writeText(text)
        reload()
        return rules.size
    }

    /** 删除导入的规则文件（对内置文件则是恢复默认） */
    fun deleteUserFile(name: String) {
        File(userDir, name).delete()
        reload()
        if (_files.value.none { it.name == name } && settings.current.activeRules == name) {
            settings.update { it.copy(activeRules = "main.json") }
        }
    }

    fun setActive(name: String) = settings.update { it.copy(activeRules = name) }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    companion object {
        const val META_BOOK_NAME = "meta[property=\"og:novel:book_name\"]"
        const val META_AUTHOR = "meta[property=\"og:novel:author\"]"
        const val META_INTRO = "meta[name=\"description\"]"
        const val META_CATEGORY = "meta[property=\"og:novel:category\"]"
        const val META_COVER_URL = "meta[property=\"og:image\"]"
        const val META_LATEST_CHAPTER = "meta[property=\"og:novel:latest_chapter_name\"]"
        const val META_LATEST_CHAPTER_URL = "meta[property=\"og:novel:latest_chapter_url\"]"
        const val META_LAST_UPDATE_TIME = "meta[property=\"og:novel:update_time\"]"
        const val META_STATUS = "meta[property=\"og:novel:status\"]"

        fun applyDefaults(rule: Rule): Rule {
            rule.language = LangType.normalize(rule.language)
            rule.search?.apply {
                if (timeout == null) timeout = 15
            }
            val book = rule.book ?: Rule.Book().also { rule.book = it }
            book.apply {
                if (timeout == null) timeout = 15
                if (bookName.isNullOrBlank()) bookName = META_BOOK_NAME
                if (author.isNullOrBlank()) author = META_AUTHOR
                if (intro.isNullOrBlank()) intro = META_INTRO
                if (coverUrl.isNullOrBlank()) coverUrl = META_COVER_URL
                if (category.isNullOrBlank()) category = META_CATEGORY
                if (latestChapter.isNullOrBlank()) latestChapter = META_LATEST_CHAPTER
                if (latestChapterUrl.isNullOrBlank()) latestChapterUrl = META_LATEST_CHAPTER_URL
                if (lastUpdateTime.isNullOrBlank()) lastUpdateTime = META_LAST_UPDATE_TIME
                if (status.isNullOrBlank()) status = META_STATUS
            }
            rule.toc?.apply {
                if (timeout == null) timeout = 60
            }
            rule.chapter?.apply {
                if (timeout == null) timeout = 15
            }
            return rule
        }

        /** 去掉 // 与 /* */ 注释（忽略字符串内部） */
        fun stripComments(src: String): String {
            val sb = StringBuilder(src.length)
            var i = 0
            var inStr = false
            var quote = '"'
            while (i < src.length) {
                val c = src[i]
                if (inStr) {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) {
                        sb.append(src[i + 1]); i += 2; continue
                    }
                    if (c == quote) inStr = false
                    i++
                    continue
                }
                if (c == '"' || c == '\'') {
                    inStr = true; quote = c; sb.append(c); i++; continue
                }
                if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                    while (i < src.length && src[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end == -1) src.length else end + 2
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}

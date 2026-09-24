package com.wang.sonovel.data

data class SearchResult(
    val sourceKey: String,
    val sourceName: String,
    val url: String,
    val bookName: String,
    val author: String? = null,
    val category: String? = null,
    val latestChapter: String? = null,
    val lastUpdateTime: String? = null,
    val status: String? = null,
    val wordCount: String? = null,
)

data class BookInfo(
    val url: String,
    val bookName: String,
    val author: String,
    val intro: String? = null,
    val category: String? = null,
    val coverUrl: String? = null,
    val latestChapter: String? = null,
    val latestChapterUrl: String? = null,
    val lastUpdateTime: String? = null,
    val status: String? = null,
)

data class ChapterRef(
    val order: Int,
    val title: String,
    val url: String,
)

/** 处理后的章节：标题 + 由 <p> 组成的正文 HTML */
data class ChapterContent(
    val order: Int,
    val title: String,
    val html: String,
)

data class SourceStatus(
    val delayMs: Int,
    val code: Int,
)

enum class ExportFormat(val ext: String, val label: String) {
    EPUB("epub", "EPUB"),
    TXT("txt", "TXT"),
    HTML("html", "HTML"),
    PDF("pdf", "PDF");

    companion object {
        fun of(ext: String?): ExportFormat = entries.firstOrNull { it.ext.equals(ext, true) } ?: EPUB
    }
}

object LangType {
    const val ZH_CN = "zh-CN"
    const val ZH_TW = "zh-TW"
    const val ZH_HANT = "zh-Hant"

    fun normalize(lang: String?): String? {
        if (lang.isNullOrBlank()) return null
        return when (lang.trim().replace('_', '-').lowercase()) {
            "zh-cn", "zh-hans", "zh-sg" -> ZH_CN
            "zh-tw" -> ZH_TW
            "zh-hant", "zh-hk", "zh-mo" -> ZH_HANT
            else -> null
        }
    }

    fun label(lang: String?): String = when (lang) {
        ZH_CN -> "简体中文"
        ZH_TW -> "繁體中文（台灣）"
        ZH_HANT -> "繁體中文"
        else -> "跟随系统"
    }
}

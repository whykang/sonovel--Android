package com.wang.sonovel.data

/**
 * 书源规则，与 so-novel 的 rules 目录下 json 格式完全兼容。
 * 字段均可为空，由 [RuleRepository] 在加载时填充默认值。
 */
class Rule {
    var id: Int = 0
    var url: String? = null
    var name: String? = null
    var comment: String? = null
    var language: String? = null
    var needProxy: Boolean = false
    var ignoreSsl: Boolean = false
    var disabled: Boolean = false

    var search: Search? = null
    var book: Book? = null
    var toc: Toc? = null
    var chapter: Chapter? = null
    var crawl: Crawl? = null

    /** 所属规则文件名（运行时填充，不参与序列化） */
    @Transient
    var file: String = ""

    val key: String get() = "$file#$id"
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: url.orEmpty()
    val searchable: Boolean get() = !disabled && search != null && search?.disabled != true && !search?.url.isNullOrBlank()

    class Search {
        var disabled: Boolean = false
        var baseUri: String? = null
        var timeout: Int? = null
        var url: String? = null
        var method: String? = null
        var data: String? = null
        var cookies: String? = null
        var result: String? = null
        var bookName: String? = null
        var author: String? = null
        var category: String? = null
        var latestChapter: String? = null
        var lastUpdateTime: String? = null
        var status: String? = null
        var wordCount: String? = null
        var nextPage: String? = null
    }

    class Book {
        var baseUri: String? = null
        var timeout: Int? = null
        var url: String? = null
        var bookName: String? = null
        var author: String? = null
        var intro: String? = null
        var category: String? = null
        var coverUrl: String? = null
        var latestChapter: String? = null
        var latestChapterUrl: String? = null
        var lastUpdateTime: String? = null
        var status: String? = null
    }

    class Toc {
        var baseUri: String? = null
        var timeout: Int? = null
        var url: String? = null
        var list: String? = null
        var item: String? = null
        var isDesc: Boolean = false
        var nextPage: String? = null
    }

    class Chapter {
        var baseUri: String? = null
        var timeout: Int? = null
        var title: String? = null
        var content: String? = null
        var paragraphTagClosed: Boolean = false
        var paragraphTag: String? = null
        var filterTxt: String? = null
        var filterTag: String? = null
        var nextPage: String? = null
        var nextPageInJs: String? = null
        var nextChapterLink: String? = null
    }

    class Crawl {
        var concurrency: Int? = null
        var minInterval: Int? = null
        var maxInterval: Int? = null
        var maxAttempts: Int? = null
        var retryMinInterval: Int? = null
        var retryMaxInterval: Int? = null
    }
}

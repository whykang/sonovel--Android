package com.wang.sonovel.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

/**
 * 对应桌面版 config.ini 的全部配置项（移除了仅桌面端有意义的 web / auto-update 等）
 */
data class AppSettings(
    // [download]
    val extName: String = "epub",
    val txtEncoding: String = "UTF-8",
    val saveToPublic: Boolean = true,
    // [source]
    val language: String = "",
    val activeRules: String = "main.json",
    val searchLimit: Int = 30,
    val searchFilter: Boolean = true,
    // [crawl]
    val concurrency: Int = -1,
    val minInterval: Int = 200,
    val maxInterval: Int = 400,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 3,
    val retryMinInterval: Int = 2000,
    val retryMaxInterval: Int = 4000,
    // [global]
    val cfBypass: String = "",
    // [cookie]
    val qidianCookie: String = "",
    val fetchBetterCover: Boolean = true,
    // [proxy]
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 7890,
    val ignoreSsl: Boolean = false,
    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    // 已停用的书源 key（file#url）
    val disabledSources: Set<String> = emptySet(),
) {
    val format: ExportFormat get() = ExportFormat.of(extName)

    /** 实际目标语言：未设置时跟随系统 */
    val targetLanguage: String get() = LangType.normalize(language) ?: systemLanguage()

    companion object {
        fun systemLanguage(): String {
            val locale = Locale.getDefault()
            if (locale.language != "zh") return LangType.ZH_CN
            return when {
                locale.country.equals("TW", true) -> LangType.ZH_TW
                locale.script.equals("Hant", true) || locale.country.equals("HK", true) ||
                    locale.country.equals("MO", true) -> LangType.ZH_HANT
                else -> LangType.ZH_CN
            }
        }
    }
}

/**
 * 某次抓取实际生效的参数：全局设置 + 书源 crawl 覆盖
 */
data class CrawlConfig(
    val concurrency: Int,
    val minInterval: Int,
    val maxInterval: Int,
    val enableRetry: Boolean,
    val maxRetries: Int,
    val retryMinInterval: Int,
    val retryMaxInterval: Int,
) {
    companion object {
        fun of(s: AppSettings, rule: Rule?): CrawlConfig {
            val c = rule?.crawl
            return CrawlConfig(
                concurrency = c?.concurrency ?: s.concurrency,
                minInterval = c?.minInterval ?: s.minInterval,
                maxInterval = c?.maxInterval ?: s.maxInterval,
                enableRetry = s.enableRetry,
                maxRetries = c?.maxAttempts ?: s.maxRetries,
                retryMinInterval = c?.retryMinInterval ?: s.retryMinInterval,
                retryMaxInterval = c?.retryMaxInterval ?: s.retryMaxInterval,
            )
        }
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val json = prefs.getString("json", null) ?: return AppSettings()
        return runCatching {
            // 与默认值合并，保证新增字段有默认值
            val loaded = gson.fromJson(json, AppSettings::class.java)
            val d = AppSettings()
            loaded.copy(
                extName = loaded.extName ?: d.extName,
                txtEncoding = loaded.txtEncoding ?: d.txtEncoding,
                language = loaded.language ?: d.language,
                activeRules = loaded.activeRules ?: d.activeRules,
                cfBypass = loaded.cfBypass ?: d.cfBypass,
                qidianCookie = loaded.qidianCookie ?: d.qidianCookie,
                proxyHost = loaded.proxyHost ?: d.proxyHost,
                themeMode = loaded.themeMode ?: d.themeMode,
                disabledSources = loaded.disabledSources ?: d.disabledSources,
            )
        }.getOrDefault(AppSettings())
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        prefs.edit().putString("json", gson.toJson(next)).apply()
    }

    fun reset() = update { AppSettings(themeMode = it.themeMode, dynamicColor = it.dynamicColor) }
}

/** 搜索历史 */
class HistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    private fun load(): List<String> =
        prefs.getString("items", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private fun save(list: List<String>) {
        _items.value = list
        prefs.edit().putString("items", list.joinToString("\n")).apply()
    }

    fun add(kw: String) {
        val k = kw.trim().replace('\n', ' ')
        if (k.isEmpty()) return
        save((listOf(k) + _items.value.filter { it != k }).take(20))
    }

    fun remove(kw: String) = save(_items.value.filter { it != kw })
    fun clear() = save(emptyList())
}

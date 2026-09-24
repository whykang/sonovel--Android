package com.wang.sonovel.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class ReaderTheme(val label: String) {
    FOLLOW("跟随应用"),
    PAPER("纸黄"),
    GREEN("护眼"),
    NIGHT("夜间");

    /** 返回 (背景, 文字)；FOLLOW 由界面使用应用主题色 */
    val colors: Pair<Color, Color>?
        get() = when (this) {
            FOLLOW -> null
            PAPER -> Color(0xFFF3E9D6) to Color(0xFF3A342A)
            GREEN -> Color(0xFFCADFC8) to Color(0xFF2B3A2C)
            NIGHT -> Color(0xFF0E0E0E) to Color(0xFF9E9E9B)
        }
}

enum class PageMode(val label: String) {
    SLIDE("左右翻页"),
    SCROLL("上下滚动"),
}

data class ReaderPrefs(
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val theme: ReaderTheme = ReaderTheme.FOLLOW,
    val keepScreenOn: Boolean = true,
    val fullScreen: Boolean = false,
    val pageMode: PageMode = PageMode.SLIDE,
)

class ReaderPrefsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ReaderPrefs> = _state.asStateFlow()

    private fun load(): ReaderPrefs = runCatching {
        prefs.getString("json", null)?.let { gson.fromJson(it, ReaderPrefs::class.java) }
            // 旧版本保存的配置可能缺少新字段（Gson 会置为 null）
            ?.let { it.copy(theme = it.theme ?: ReaderTheme.FOLLOW, pageMode = it.pageMode ?: PageMode.SLIDE) }
    }.getOrNull() ?: ReaderPrefs()

    fun update(block: (ReaderPrefs) -> ReaderPrefs) {
        val next = block(_state.value)
        _state.value = next
        prefs.edit().putString("json", gson.toJson(next)).apply()
    }
}

/** 每本书的阅读进度：章节序号 + 列表位置 */
data class ReadingPosition(val chapter: Int, val item: Int, val offset: Int, val total: Int) {
    val percent: Int get() = if (total <= 0) 0 else ((chapter + 1) * 100 / total).coerceIn(1, 100)
}

class ReadingProgressRepository(context: Context) {
    private val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** 进度变化计数，供界面刷新用 */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun key(file: File) = file.name

    fun get(file: File): ReadingPosition? {
        val raw = prefs.getString(key(file), null) ?: return null
        val p = raw.split(':').mapNotNull { it.toIntOrNull() }
        if (p.size < 4) return null
        return ReadingPosition(p[0], p[1], p[2], p[3])
    }

    fun save(file: File, pos: ReadingPosition) {
        prefs.edit().putString(key(file), "${pos.chapter}:${pos.item}:${pos.offset}:${pos.total}").apply()
        _version.value++
    }

    fun clear(file: File) {
        prefs.edit().remove(key(file)).apply()
        _version.value++
    }
}

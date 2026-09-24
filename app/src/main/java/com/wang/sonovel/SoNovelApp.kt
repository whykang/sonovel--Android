package com.wang.sonovel

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.wang.sonovel.core.ChineseConverter
import com.wang.sonovel.core.Http
import com.wang.sonovel.core.JsEngine
import com.wang.sonovel.data.HistoryRepository
import com.wang.sonovel.data.LibraryRepository
import com.wang.sonovel.data.RuleRepository
import com.wang.sonovel.data.SettingsRepository
import com.wang.sonovel.download.DownloadManager
import com.wang.sonovel.download.DownloadService
import com.wang.sonovel.reader.ReaderPrefsRepository
import com.wang.sonovel.reader.ReadingProgressRepository
import kotlin.concurrent.thread

/** 简单的依赖容器 */
class AppGraph(context: Context) {
    val settings = SettingsRepository(context)
    val rules = RuleRepository(context, settings)
    val history = HistoryRepository(context)
    val library = LibraryRepository(context)
    val downloads = DownloadManager(context, settings, rules, library)
    val readerPrefs = ReaderPrefsRepository(context)
    val progress = ReadingProgressRepository(context)
}

class SoNovelApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        ChineseConverter.init(this)
        graph = AppGraph(this)
        DownloadService.createChannels(this)
        // 预加载 QuickJS 原生库
        thread(name = "js-init") { runCatching { JsEngine.init() } }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client(graph.settings.current) }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(50L * 1024 * 1024).build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

val Context.graph: AppGraph get() = (applicationContext as SoNovelApp).graph

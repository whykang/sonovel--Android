package com.wang.sonovel.core

import android.content.Context
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure
import com.wang.sonovel.BuildConfig

/**
 * 友盟统计封装。AppKey 来自 local.properties 的 umeng.appkey，未配置时不启用。
 */
object Analytics {
    /** 是否配置了 AppKey */
    val available: Boolean get() = BuildConfig.UMENG_APPKEY.isNotBlank()

    @Volatile
    private var started = false

    private lateinit var appContext: Context

    /** Application.onCreate 中调用：初始化并开始统计启动次数、使用时长等 */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (!available || started) return
        started = true
        runCatching {
            UMConfigure.setLogEnabled(BuildConfig.DEBUG)
            UMConfigure.preInit(appContext, BuildConfig.UMENG_APPKEY, BuildConfig.UMENG_CHANNEL)
            UMConfigure.submitPolicyGrantResult(appContext, true)
            UMConfigure.init(appContext, BuildConfig.UMENG_APPKEY, BuildConfig.UMENG_CHANNEL, UMConfigure.DEVICE_TYPE_PHONE, "")
            MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.AUTO)
        }
    }

    /** 自定义事件。只上报功能使用情况，不包含搜索关键词、书名等内容 */
    fun event(name: String, params: Map<String, Any> = emptyMap()) {
        if (!started) return
        runCatching { MobclickAgent.onEventObject(appContext, name, params) }
    }
}

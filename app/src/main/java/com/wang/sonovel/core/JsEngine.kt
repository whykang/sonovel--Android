package com.wang.sonovel.core

import com.google.gson.Gson
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext

/**
 * 基于 QuickJS 的 JS 执行器（内置原生库，支持 ES2023，不依赖系统 WebView）。
 * 对应桌面版 Javet(V8) 的 JsCaller。
 */
object JsEngine {
    private val gson = Gson()

    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                QuickJSLoader.init()
                initialized = true
            }
        }
    }

    /**
     * 以 r 为入参执行代码片段，等价于：function func(r) { code; return r; }
     */
    fun call(code: String, input: String?): String {
        val script = """
            (function(){
              function func(r) {
                $code;
                return r;
              }
              var __res = func(${gson.toJson(input ?: "")});
              return __res === undefined || __res === null ? '' : String(__res);
            })()
        """.trimIndent()
        return eval(script)
    }

    fun eval(script: String): String {
        init()
        // QuickJSContext 需在创建线程中使用，这里每次调用独立创建，保证线程安全
        val ctx = QuickJSContext.create()
        try {
            ctx.setMaxStackSize(1024 * 1024)
            val result = ctx.evaluate(script)
            return result?.toString() ?: ""
        } finally {
            ctx.destroy()
        }
    }
}

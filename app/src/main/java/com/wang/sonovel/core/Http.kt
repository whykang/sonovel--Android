package com.wang.sonovel.core

import android.annotation.SuppressLint
import com.wang.sonovel.data.AppSettings
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/** 抓取到的页面：最终 URL（跟随重定向后）+ 原始字节 + 响应声明的编码 */
class FetchedPage(val url: String, val bytes: ByteArray, val charset: String?, val code: Int) {
    val text: String by lazy { decodeHtml(bytes, charset) }

    fun document(baseUri: String?): Document =
        Jsoup.parse(ByteArrayInputStream(bytes), charset, baseUri?.takeIf { it.isNotBlank() } ?: url)
}

object Http {
    private const val TIMEOUT = 10L

    @Volatile
    private var cache: Pair<String, OkHttpClient>? = null
    @Volatile
    private var unsafeCache: Pair<String, OkHttpClient>? = null

    /** 根据设置获取（缓存的）客户端 */
    fun client(s: AppSettings, unsafe: Boolean = false): OkHttpClient {
        val sig = "${s.proxyEnabled}|${s.proxyHost}|${s.proxyPort}|${s.ignoreSsl}"
        val useUnsafe = unsafe || s.ignoreSsl
        val cached = if (useUnsafe) unsafeCache else cache
        if (cached != null && cached.first == sig) return cached.second
        val client = build(s, useUnsafe)
        if (useUnsafe) unsafeCache = sig to client else cache = sig to client
        return client
    }

    private fun build(s: AppSettings, unsafe: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val nb = req.newBuilder().header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                if (req.header("User-Agent") == null) nb.header("User-Agent", RandomUA.generate())
                chain.proceed(nb.build())
            }
        if (s.proxyEnabled && s.proxyHost.isNotBlank() && s.proxyPort > 0) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort)))
        }
        if (unsafe) applyUnsafeSsl(b)
        return b.build()
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun applyUnsafeSsl(b: OkHttpClient.Builder) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier(HostnameVerifier { _, _ -> true })
    }

    fun referer(url: String): String = runCatching {
        val u = URI(url)
        "${u.scheme}://${u.authority}/"
    }.getOrDefault(url)

    /** 执行请求（带超时）并读取全部内容 */
    fun execute(client: OkHttpClient, request: Request, timeoutSec: Int?): FetchedPage {
        val call = client.newCall(request)
        call.timeout().timeout((timeoutSec ?: 15).toLong(), TimeUnit.SECONDS)
        call.execute().use { resp -> return resp.toPage() }
    }

    fun get(client: OkHttpClient, url: String, timeoutSec: Int?, headers: Map<String, String> = emptyMap()): FetchedPage {
        val rb = Request.Builder().url(url)
            .header("User-Agent", RandomUA.generate())
            .header("Referer", referer(url))
        headers.forEach { (k, v) -> rb.header(k, v) }
        return execute(client, rb.build(), timeoutSec)
    }

    private fun Response.toPage(): FetchedPage {
        val bytes = body?.bytes() ?: ByteArray(0)
        if (!isSuccessful && bytes.isEmpty()) throw IOException("HTTP $code")
        return FetchedPage(request.url.toString(), bytes, body?.contentType()?.charset()?.name(), code)
    }

    /** POST 表单：值为 %s 的字段依次替换为参数 */
    fun buildForm(json: String?, vararg args: String): RequestBody {
        val fb = FormBody.Builder()
        if (!json.isNullOrBlank()) {
            val obj = JSONObject(json)
            var i = 0
            obj.keys().forEach { k ->
                val v = obj.opt(k)?.toString().orEmpty()
                if (v == "%s") {
                    if (i < args.size) fb.add(k, args[i++])
                } else fb.add(k, v)
            }
        }
        return fb.build()
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

private val CF_TITLES = setOf(
    "Just a moment...",
    "403 Forbidden",
    "Attention Required",
    "Checking your browser before accessing",
)

/** 页面是否为 Cloudflare 真人验证 */
fun Document?.hasCloudflare(): Boolean = this != null && title().trim() in CF_TITLES

/**
 * 若页面被 Cloudflare 拦截，通过 cf-bypass 服务 (CloudflareBypassForScraping) 获取真实页面
 */
fun bypassCloudflareIfNeeded(doc: Document, url: String, s: AppSettings, what: String): Document {
    if (!doc.hasCloudflare()) return doc
    if (s.cfBypass.isBlank()) {
        throw IOException("$what 存在 Cloudflare 真人验证，请在设置中配置 cf-bypass 服务地址")
    }
    val api = s.cfBypass.trimEnd('/') + "/html?url=" + Http.encode(url)
    val page = Http.get(Http.client(s), api, 60)
    return Jsoup.parse(page.text, url)
}

/** 按 HTTP 头或 <meta> 声明的编码解码 HTML，缺省 UTF-8（兼容 GBK 站点） */
fun decodeHtml(bytes: ByteArray, declared: String?): String {
    val cs = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        ?: sniffCharset(bytes)
        ?: Charsets.UTF_8
    return String(bytes, cs)
}

private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?\s*([\w-]+)""", RegexOption.IGNORE_CASE)

private fun sniffCharset(bytes: ByteArray): Charset? {
    val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
    val name = META_CHARSET.find(head)?.groupValues?.get(1) ?: return null
    return runCatching {
        // gb2312 实际多为 GBK 编码
        if (name.equals("gb2312", true)) Charset.forName("GBK") else Charset.forName(name)
    }.getOrNull()
}

object RandomUA {
    private val OS = arrayOf(
        "Windows NT 10.0; Win64; x64",
        "Windows NT 11.0; Win64; x64",
        "Macintosh; Intel Mac OS X 10_15_7",
        "X11; Linux x86_64",
    )

    fun generate(): String {
        val os = OS[Random.nextInt(OS.size)]
        val major = Random.nextInt(110, 141)
        val build = Random.nextInt(1000, 7000)
        return when (Random.nextInt(3)) {
            0 -> "Mozilla/5.0 ($os; rv:$major.0) Gecko/20100101 Firefox/$major.0"
            1 -> "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.$build.0 Safari/537.36 Edg/$major.0.0.0"
            else -> "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.$build.0 Safari/537.36"
        }
    }
}

package com.adbcontrol.controlled.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub 直连加速下载器(GitHub520 思路的进程内实现,无需 root)。
 *
 * 原理:OkHttp 连接前先经 [okhttp3.Dns] 解析域名。这里替换为自定义 DNS ——
 * 对 github.com / objects.githubusercontent.com 等 Release 下载相关域名使用
 * GitHub520 优选 IP 表(启动快照 + 定期从 hosts 源刷新,SharedPreferences 缓存),
 * TLS/SNI 与证书校验仍按原域名进行,安全性不变;其他域名回退系统解析。
 *
 * 回退链(并发竞速,谁先通就用谁,其余自动取消):
 * 1. 直连(优选 IP DNS)
 * 2. ghfast.top / gh-proxy.com / ghproxy.net 公共加速前缀
 * 单个代理失效不影响整体——只要还有一个源可用就能下载。
 * 所有源 30s 内都无响应才报失败。
 */
@Singleton
class GitHubFastDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Release 下载链路上会出现的主机(302 跳转目标也覆盖)。 */
    private val targetHosts = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "codeload.github.com",
        "release-assets.githubusercontent.com",
    )

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** host → 优选 IP 列表 的缓存序列化器。 */
    private val hostMapSerializer =
        MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** GitHub520 hosts 内容源(纯文本,"IP 域名" 每行一条)。 */
    private val hostsSource = "https://raw.hellogithub.com/hosts"

    /** 公共加速前缀(并发竞速,谁先通就用谁;失效不影响其他)。 */
    private val proxyPrefixes = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /** 优选 IP 缓存读取(过期则异步刷新,不阻塞调用方)。 */
    private fun cachedHosts(): Map<String, List<String>> {
        if (isStale()) refreshAsync()
        return runCatching {
            json.decodeFromString(
                hostMapSerializer,
                prefs.getString(KEY_IP_MAP, "{}") ?: "{}",
            )
        }.getOrElse { emptyMap() }
    }

    private fun isStale(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L) > STALE_MS

    private fun refreshAsync() {
        refreshScope.launch { runCatching { refreshBlocking() } }
    }

    private suspend fun refreshIfStaleBlocking() {
        if (!isStale()) return
        runCatching { refreshBlocking() }
    }

    /** 拉取并解析 GitHub520 hosts,只保留 [targetHosts] 内的映射。 */
    private suspend fun refreshBlocking() = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(hostsSource).build()
        plainClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext
            val map = mutableMapOf<String, MutableList<String>>()
            resp.body?.string().orEmpty().lineSequence().forEach { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEach
                val parts = t.split(WHITESPACE)
                if (parts.size < 2) return@forEach
                val ip = parts[0]
                val host = parts[1].lowercase()
                if (host in targetHosts && IP_REGEX.matches(ip)) {
                    map.getOrPut(host) { mutableListOf() }.add(ip)
                }
            }
            if (map.isNotEmpty()) {
                prefs.edit()
                    .putString(KEY_IP_MAP, json.encodeToString(hostMapSerializer, map))
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "github fast hosts refreshed: ${map.values.sumOf { it.size }} entries")
            }
        }
    }

    /**
     * 自定义 DNS:命中缓存的 GitHub 域名直接返回优选 IP;
     * 未命中 / 全部失效时回退系统解析(Dns.SYSTEM)。
     */
    private val fastDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ips = cachedHosts()[hostname.lowercase()]
            if (ips.isNullOrEmpty()) return Dns.SYSTEM.lookup(hostname)
            val resolved = ips.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            return resolved.ifEmpty { Dns.SYSTEM.lookup(hostname) }
        }
    }

    /** GitHub 直连客户端:优选 IP DNS + 较长读超时(APK 可能几十 MB)。 */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(fastDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 加速代理客户端(代理主机本身用系统 DNS)。 */
    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 非 GitHub 的轻量请求(拉 hosts 源等)。 */
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载文件到 [target]:并发竞速所有源(直连 + 代理),谁先通就用谁下载。
     * 单个代理失效不影响整体——只要还有一个源可用就能成功。
     * 所有源 30s 内都无响应才抛异常。
     */
    suspend fun download(url: String, target: File, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            refreshIfStaleBlocking()
            val sources = buildList {
                add(url to client) // 直连(优选 IP)
                proxyPrefixes.forEach { prefix -> add((prefix + url) to proxyClient) }
            }
            // 并发探测:所有源同时发 HEAD,谁先返回 200+有 body 就用谁
            val (winUrl, winClient) = try {
                pickFastestSource(sources)
            } catch (t: Throwable) {
                Log.e(TAG, "all sources unreachable within 30s: ${t.message}")
                throw IOException("所有下载源均不可用(直连+${proxyPrefixes.size}个代理),请检查网络后重试", t)
            }
            Log.i(TAG, "race winner: $winUrl")
            downloadOnce(winUrl, winClient, target, onProgress)
        }

    /**
     * 并发竞速选最快可达的源。
     * 每个源发 HEAD 请求:成功(HTTP 200 + Content-Length>0)就立即返回;
     * 失败/超时的源挂起(不返回),这样 select 只会选中第一个成功的源。
     * 全部失败则 30s 超时抛出。
     */
    private suspend fun pickFastestSource(
        sources: List<Pair<String, OkHttpClient>>,
    ): Pair<String, OkHttpClient> = coroutineScope {
        val deferreds = sources.map { (url, c) ->
            async(Dispatchers.IO) {
                val ok = runCatching {
                    c.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                        resp.isSuccessful &&
                            (resp.header("Content-Length")?.toLongOrNull() ?: 0L) > 0L
                    }
                }.getOrDefault(false)
                if (ok) url to c else awaitCancellation() // 失败挂起,不参与竞速
            }
        }
        try {
            withTimeout(30_000L) {
                select<Pair<String, OkHttpClient>> {
                    deferreds.forEach { d -> d.onAwait { it } }
                }
            }
        } finally {
            deferreds.forEach { it.cancel() } // 取消所有探测(无论赢没赢)
        }
    }

    private fun downloadOnce(
        url: String,
        c: OkHttpClient,
        target: File,
        onProgress: (Int) -> Unit,
    ): File {
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + ".part")
        c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            val body = resp.body ?: error("empty body for $url")
            val total = body.contentLength()
            tmp.outputStream().use { out ->
                val source = body.byteStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read += n
                    if (total > 0) onProgress(((read * 100) / total).toInt())
                }
            }
            if (tmp.length() == 0L) error("zero-byte response for $url")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) error("rename failed: $tmp")
        return target
    }

    companion object {
        private const val TAG = "GitHubFastDl"
        private const val PREFS_NAME = "github_fast"
        private const val KEY_IP_MAP = "ip_map_json"
        private const val KEY_FETCHED_AT = "fetched_at"

        /** 优选 IP 刷新周期:24h(GitHub520 上游约每天更新多次)。 */
        private const val STALE_MS = 24L * 60 * 60 * 1000

        private val IP_REGEX = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private val WHITESPACE = Regex("\\s+")
    }
}

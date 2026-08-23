package com.adbcontrol.controlled.telemetry

import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.adbcontrol.controlled.config.ConfigStore
import com.adbcontrol.controlled.net.MqttManager
import com.adbcontrol.controlled.storage.R2StorageClient
import com.adbcontrol.shared.MessageType
import com.adbcontrol.shared.model.UsageItem
import com.adbcontrol.shared.model.UsageReport
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 应用使用时长聚合上报。README 5.5 / 10.2.3 缓解策略 #5。
 *
 * - 整点上报,按 deviceId hash 错峰到整点内不同分钟(offset = abs(deviceId.hashCode()) % 60)
 * - UsageStatsManager.queryAndAggregateUsageStats 取当日各包时长
 * - 每条附应用名 + 官方图标(R2 公开读,icons/{pkg}.png,每包只上传一次),Web 端直接展示
 * - QoS 1
 */
class UsageReporter(
    private val context: Context,
    private val mqttManager: MqttManager,
    private val json: Json,
    private val configStore: ConfigStore,
) {

    /** 错峰偏移分钟(README 10.2.3 #5)。 */
    fun offsetMinutes(deviceId: String): Int = kotlin.math.abs(deviceId.hashCode()) % 60

    /** 已成功上传到 R2 的图标包名集合(持久化,进程重启不重复上传)。 */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** 采集当日各 App 使用时长并上报。 */
    fun reportOnce(deviceId: String, userId: String = "0"): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val stats = runCatching {
            usm.queryAndAggregateUsageStats(start, end)
        }.getOrElse {
            Log.w(TAG, "queryAndAggregateUsageStats failed", it)
            return false
        }

        val pm = context.packageManager
        val items = stats.orEmpty().mapNotNull { (pkg, stat) ->
            val minutes = (stat.totalTimeInForeground / 60_000L).toInt()
            if (minutes <= 0) return@mapNotNull null
            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            UsageItem(pkg = pkg, appName = appName, usageMinutes = minutes)
        }.sortedByDescending { it.usageMinutes }

        // 只给 Top N 解析/上传图标,控制耗时与 R2 请求数
        val r2cfg = runCatching { configStore.load()?.r2 }.getOrNull()
        val r2 = if (r2cfg != null && r2cfg.publicRead) {
            runCatching { R2StorageClient(r2cfg) }.getOrNull()
        } else null
        val enriched = items.mapIndexed { i, item ->
            if (i < ICON_TOP_N && r2 != null) {
                item.copy(iconUrl = resolveIconUrl(item.pkg, item.appName, pm, r2))
            } else item
        }

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(start))
        val report = UsageReport(
            deviceId = deviceId,
            userId = userId,
            date = dateStr,
            items = enriched,
            timestamp = System.currentTimeMillis(),
        )
        val payload = json.encodeToString(UsageReport.serializer(), report)
        return mqttManager.publishTelemetry(
            MessageType.PUSH_DATA, payload, "usage/$deviceId", qos = 1
        )
    }

    /**
     * 返回该包官方图标的公开 URL;首次见到时把 launcher 图标缩放到 [ICON_PX] 存本地缓存并
     * 上传 R2(失败不影响本次上报,下次再试)。全程 best-effort,异常一律返回 null。
     */
    private fun resolveIconUrl(
        pkg: String,
        appName: String?,
        pm: android.content.pm.PackageManager,
        r2: R2StorageClient,
    ): String? = runCatching {
        val key = "$ICON_KEY_PREFIX$pkg.png"
        if (!prefs.getStringSet(UPLOADED_KEY, emptySet()).orEmpty().contains(pkg)) {
            val cache = File(File(context.filesDir, ICON_CACHE_DIR), "$pkg.png")
            val bytes = if (cache.exists()) {
                cache.readBytes()
            } else {
                val drawable = pm.getApplicationIcon(pkg)
                val bitmap = drawableToBitmap(drawable, ICON_PX) ?: return@runCatching null
                val out = File(context.filesDir, ICON_CACHE_DIR).apply { mkdirs() }
                    .let { File(it, "$pkg.png") }
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                runCatching { bitmap.recycle() }
                out.readBytes()
            }
            // reportOnce 由 TelemetryEngine 的非挂起调度块调用(已在 IO 线程),
            // 这里用 runBlocking 桥接 R2 的 suspend upload 是安全的
            kotlinx.coroutines.runBlocking {
                runCatching { r2.upload(key, bytes, "image/png") }.getOrThrow()
            }
            prefs.edit()
                .putStringSet(UPLOADED_KEY, prefs.getStringSet(UPLOADED_KEY, emptySet()).orEmpty() + pkg)
                .apply()
        }
        r2.publicUrl(key)
    }.onFailure {
        Log.w(TAG, "resolveIconUrl failed for $pkg (${appName}): ${it.message}")
    }.getOrNull()

    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap? {
        (d as? BitmapDrawable)?.bitmap?.let { return it }
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val scale = size.toFloat() / maxOf(w, h)
        val bw = (w * scale).toInt().coerceIn(1, size)
        val bh = (h * scale).toInt().coerceIn(1, size)
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            d.setBounds(0, 0, bw, bh)
            d.draw(canvas)
        }
    }

    companion object {
        private const val TAG = "UsageReporter"
        private const val PREFS = "usage_icon_upload"
        private const val UPLOADED_KEY = "uploaded_pkgs"
        private const val ICON_CACHE_DIR = "icons"
        private const val ICON_KEY_PREFIX = "icons/"
        private const val ICON_TOP_N = 15
        private const val ICON_PX = 96
    }
}

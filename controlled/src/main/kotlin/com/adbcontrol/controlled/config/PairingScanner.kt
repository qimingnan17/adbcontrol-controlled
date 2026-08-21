package com.adbcontrol.controlled.config

import android.content.Context
import com.adbcontrol.shared.model.PairTokenPayload
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * QR 配对扫码器。README 8.3。
 *
 * 用 MLKit GmsBarcodeScanner(无需 CameraX 配置,系统级扫码 UI)。
 * 解析 QR 为 [PairTokenPayload]。
 */
class PairingScanner(context: Context) {

    private val scanner = GmsBarcodeScanning.getClient(context)

    /** 启动扫码,返回解析出的 [PairTokenPayload] 或异常。 */
    suspend fun scan(): PairTokenPayload = suspendCancellableCoroutine { cont ->
        // GMS Task<Barcode> 没有 cancel(),只能用 isActive 过滤回调。
        // 如果协程被取消,回调里通过 cont.isActive 检查跳过 resume,避免资源泄漏。
        val task = scanner.startScan()
        task
            .addOnSuccessListener { barcode ->
                if (!cont.isActive) return@addOnSuccessListener // 协程已取消(如用户退出),不再 resume
                val raw = barcode.rawValue
                if (raw == null) {
                    cont.resumeWithException(IllegalStateException("empty barcode"))
                    return@addOnSuccessListener
                }
                runCatching { parse(raw) }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { if (cont.isActive) cont.resumeWithException(it) }
            }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    private fun parse(raw: String): PairTokenPayload {
        // 支持 {pairToken,serverUrl,deviceId,...} JSON 与 "pairToken|serverUrl|deviceId" 文本
        return runCatching {
            json.decodeFromString(PairTokenPayload.serializer(), raw)
        }.getOrElse {
            // 回退:简单管道分隔
            val parts = raw.split("|")
            if (parts.size >= 3) {
                PairTokenPayload(
                    pairToken = parts[0],
                    serverUrl = parts[1],
                    deviceId = parts[2],
                    deviceName = parts.getOrNull(3),
                )
            } else error("invalid QR payload")
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile private var instance: PairingScanner? = null
        fun get(context: Context): PairingScanner =
            instance ?: synchronized(this) {
                instance ?: PairingScanner(context.applicationContext).also { instance = it }
            }
    }
}

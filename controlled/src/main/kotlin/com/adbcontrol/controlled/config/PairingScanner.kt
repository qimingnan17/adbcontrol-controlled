package com.adbcontrol.controlled.config

import android.content.Context
import com.adbcontrol.shared.model.PairTokenPayload

/**
 * QR 配对载荷解析。README 8.3。
 *
 * 相机界面由 zxing-android-embedded 的 CaptureActivity 提供(离线、免 GMS,
 * 见 MainActivity 的 ScanContract 调用);本类只负责把扫到的文本解析为
 * [PairTokenPayload]。原 ML Kit GmsBarcodeScanner 依赖 Google Play 运行时
 * 下载扫码模块,国行 ROM 不可用,已移除。
 */
class PairingScanner {

    /** 解析扫码结果:支持 {pairToken,serverUrl,deviceId,...} JSON 与 "pairToken|serverUrl|deviceId" 文本。 */
    fun parse(raw: String): PairTokenPayload {
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
                instance ?: PairingScanner().also { instance = it }
            }
    }
}

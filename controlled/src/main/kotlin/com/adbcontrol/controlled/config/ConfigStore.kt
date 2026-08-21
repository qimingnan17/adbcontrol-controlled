package com.adbcontrol.controlled.config

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.adbcontrol.shared.model.AppConfig
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 凭证加密落盘。设计参见 README 8.3.2。
 *
 * - 用 androidx.security.crypto.EncryptedFile(Android Keystore 包裹的 AES-GCM)
 * - allowBackup=false,凭证不进备份
 * - 失败时返回 null,由上层触发重新配对
 */
class ConfigStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val configFile: File by lazy {
        File(appContext.filesDir, "app_config.enc")
    }

    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            configFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /** 保存配置(配对或续期后调用)。 */
    fun save(config: AppConfig) {
        if (configFile.exists()) {
            configFile.delete()
        }
        val data = json.encodeToString(AppConfig.serializer(), config).toByteArray(Charsets.UTF_8)
        encryptedFile().openFileOutput().use { it.write(data) }
    }

    /** 读取已配对配置。无配置或损坏时返回 null。 */
    fun load(): AppConfig? = runCatching {
        if (!configFile.exists()) return null
        encryptedFile().openFileInput().use { input ->
            val text = input.bufferedReader().readText()
            json.decodeFromString(AppConfig.serializer(), text)
        }
    }.getOrNull()

    /** 是否已配对。 */
    fun isPaired(): Boolean = configFile.exists()

    /** 清除凭证(注销设备时调用)。 */
    fun clear() {
        if (configFile.exists()) {
            configFile.delete()
        }
    }
}

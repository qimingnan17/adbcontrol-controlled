package com.adbcontrol.controlled.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey

/**
 * 设备身份密钥对。README 8.3.1 配对时附 pubKey 证明身份。
 *
 * - 用 Android Keystore 生成并保管 EC P-256 密钥对(硬件隔离)
 * - 公钥 Base64 编码,配对时上送;私钥永不离开 Keystore
 * - sessionKey(HMAC)才是消息签名实际密钥,pubKey 用于设备绑定证明
 */
object DeviceIdentity {

    private const val ALIAS = "adbcontrol_device_identity"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREF_FILE = "adbcontrol_device_identity"
    private const val KEY_EPHEMERAL = "ephemeral_ec_priv"

    fun ensurePubKey(context: Context): String {
        return runCatching {
            Base64.encodeToString(loadOrGenerate(context).encoded, Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "keystore EC failed, fallback ephemeral", it)
            fallbackEphemeral(context)
        }
    }

    private fun loadOrGenerate(context: Context): PublicKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing.certificate.publicKey

        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        gen.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setInvalidatedByBiometricEnrollment(false)
                .build()
        )
        val pair: KeyPair = gen.generateKeyPair()
        return pair.public
    }

    /**
     * Keystore 不可用时的兜底公钥。
     *
     * C6: 原实现每次调用都生成新 EC 密钥对,导致 pair 与 renew 之间 pubKey 不一致,
     * 后端无法匹配 → 续期失败。现把 fallback 公钥持久化到 EncryptedSharedPreferences
     * (key 为 "ephemeral_ec_priv"),保证同一设备 pubKey 稳定。EncryptedSharedPreferences
     * 自身失败时再退化为内存级(弱安全,仅占位)。
     */
    private fun fallbackEphemeral(context: Context): String {
        val prefs = encryptedPrefs(context)
        if (prefs != null) {
            prefs.getString(KEY_EPHEMERAL, null)?.let { existing ->
                if (existing.isNotEmpty()) return existing
            }
        }
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(256)
        val pub = Base64.encodeToString(gen.generateKeyPair().public.encoded, Base64.NO_WRAP)
        runCatching { prefs?.edit()?.putString(KEY_EPHEMERAL, pub)?.apply() }
        return pub
    }

    private fun encryptedPrefs(context: Context): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    private const val TAG = "DeviceIdentity"
}

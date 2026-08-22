package com.adbcontrol.controlled.config

import android.content.Context
import android.util.Log
import com.adbcontrol.shared.model.AppConfig
import com.adbcontrol.shared.model.BrokerConfig
import com.adbcontrol.shared.model.PairTokenPayload
import com.adbcontrol.shared.model.PairingResponse
import com.adbcontrol.shared.model.RenewRequest
import com.adbcontrol.shared.model.RenewResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 配对客户端。README 8.3。
 *
 * 流程:
 * 1. 被控端扫码 → 用 pairToken POST server /pair(附 deviceId/deviceName/pubKey)
 * 2. server 校验 → 下发临时凭证 [PairingResponse]
 * 3. 加密落盘为 [AppConfig]
 * 4. 临时凭证临期时 POST /renew 续签
 */
class PairingClient(
    context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {

    private val appContext = context.applicationContext

    /** 设备公钥(Base64),配对时随请求上送证明身份。 */
    val pubKey: String by lazy { DeviceIdentity.ensurePubKey(appContext) }

    /** 调用 /pair。返回配对响应或抛异常。 */
    suspend fun pair(payload: PairTokenPayload, deviceName: String): PairingResponse = withContext(Dispatchers.IO) {
        // 用 buildJsonObject 构造,确保任意 deviceName/pubKey 字符正确转义,防 JSON 注入
        val body = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("pairToken", payload.pairToken)
                // 后端 PairTokenPayload 的 serverUrl 为必填(无默认值),缺失会反序列化 400
                put("serverUrl", payload.serverUrl)
                put("deviceId", payload.deviceId)
                put("deviceName", deviceName)
                put("pubKey", pubKey)
            },
        )
        val response = post("${payload.serverUrl.trimEnd('/')}/pair", body)
        json.decodeFromString(PairingResponse.serializer(), response)
    }

    /** 调用 /renew 续签。README 8.3.1 第 5 步。 */
    suspend fun renew(serverUrl: String, deviceId: String, pairToken: String): RenewResponse = withContext(Dispatchers.IO) {
        val req = RenewRequest(deviceId = deviceId, pairToken = pairToken)
        val body = json.encodeToString(RenewRequest.serializer(), req)
        val response = post("${serverUrl.trimEnd('/')}/renew", body)
        json.decodeFromString(RenewResponse.serializer(), response)
    }

    private fun post(url: String, jsonBody: String): String {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "POST $url failed ${resp.code}: $body")
                error("server returned ${resp.code}: $body")
            }
            return body
        }
    }

    companion object {
        private const val TAG = "PairingClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultOkHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

package com.adbcontrol.controlled.storage

import android.util.Log
import com.adbcontrol.shared.model.R2Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI

/**
 * Cloudflare R2 对象存储客户端。README 10.4。
 *
 * 用途:截屏 PNG / 录屏 / 文件传输 / APK 差分包等 > 100KB 大对象走 HTTP 旁路,
 * MQTT 只回传 URL(EMQX Serverless 单消息 1MB 上限)。
 *
 * R2 与 AWS S3 协议兼容,直接用 AWS SDK 指定 endpoint + region=auto。
 */
class R2StorageClient(
    private val config: R2Config,
) {

    private val s3: S3Client by lazy { buildClient(config) }

    /**
     * 上传字节流到 R2。
     * @param key 对象 key,如 screenshots/{deviceId}/{ts}.png
     * @return 可访问的 URL(public bucket 时直接拼)
     */
    suspend fun upload(key: String, bytes: ByteArray, contentType: String = "application/octet-stream"): String =
        withContext(Dispatchers.IO) {
            if (bytes.size > MAX_UPLOAD_BYTES) {
                throw IllegalArgumentException("payload too large: ${bytes.size} > $MAX_UPLOAD_BYTES")
            }
            runCatching {
                val request = PutObjectRequest.builder()
                    .bucket(config.bucket)
                    .key(key)
                    .contentType(contentContent(contentType))
                    .build()
                s3.putObject(request, RequestBody.fromBytes(bytes))
                publicUrl(key)
            }.getOrElse {
                Log.e(TAG, "upload $key failed", it)
                throw it
            }
        }

    private fun contentContent(ct: String): String = ct

    /** 拼可访问 URL。public read 时直接拼 endpoint/bucket/key。 */
    fun publicUrl(key: String): String {
        val endpoint = config.endpoint.trimEnd('/')
        return if (config.publicRead) {
            "$endpoint/${config.bucket}/$key"
        } else {
            // private:后续用 presigned URL(留 TODO)
            "$endpoint/${config.bucket}/$key"
        }
    }

    private fun buildClient(c: R2Config): S3Client {
        val creds = AwsBasicCredentials.create(c.accessKey, c.accessSecret)
        return S3Client.builder()
            .region(Region.of(c.region))
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .endpointOverride(URI.create(c.endpoint))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true) // R2 推荐 path-style
                    .build()
            )
            .build()
    }

    companion object {
        private const val TAG = "R2StorageClient"
        const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
    }
}

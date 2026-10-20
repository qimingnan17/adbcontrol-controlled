package com.adbcontrol.shared

import com.adbcontrol.shared.model.ExecutionResult
import com.adbcontrol.shared.model.UpdateCheckResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolVersionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun missingFieldIsLegacy() {
        assertEquals(ProtocolVersion.LEGACY, ProtocolVersion.detect("""{"id":"a","type":"COMMAND"}"""))
    }

    @Test
    fun presentFieldIsRead() {
        assertEquals(2, ProtocolVersion.detect("""{"id":"a","protocolVersion":2}"""))
    }

    @Test
    fun unparseableIsNull() {
        assertNull(ProtocolVersion.detect("not json"))
    }

    @Test
    fun encodeAlwaysEmitsVersion() {
        // 即使 encodeDefaults=false,@EncodeDefault 也保证字段输出
        val s = Json.encodeToString(ExecutionResult.serializer(), ExecutionResult(commandId = "c", success = true, channel = "Shizuku"))
        assertTrue(s.contains("\"protocolVersion\":2"), s)
        assertTrue(s.contains("\"channel\":\"Shizuku\""), s)
    }

    @Test
    fun legacyUpdateResponseWithoutShaDecodes() {
        val r = json.decodeFromString(UpdateCheckResponse.serializer(), """{"hasUpdate":true}""")
        assertNull(r.sha256)
    }
}

package bosca.storage.service

import bosca.serialization.OffsetDateTimeSerializer
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectStorageInfoTest {
    private val json = Json {
        serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) }
    }

    @Test
    fun `explicit serialization preserves large lengths opaque identifiers and timestamp precision`() {
        val info = ObjectStorageInfo(
            length = Long.MAX_VALUE,
            etag = "\"multipart-value-3\"",
            version = "opaque/+version",
            modified = OffsetDateTime.parse("2026-10-17T01:02:03.123456789Z"),
        )
        val encoded = json.encodeToString(ObjectStorageInfo.serializer(), info)
        assertEquals(info, json.decodeFromString(ObjectStorageInfo.serializer(), encoded))
    }

    @Test
    fun `provider metadata can be absent without inventing revision or digest values`() {
        assertEquals(ObjectStorageInfo(0), json.decodeFromString(ObjectStorageInfo.serializer(), "{\"length\":0}"))
    }

    @Test
    fun `unknown or invalid negative lengths are rejected`() {
        assertFailsWith<IllegalArgumentException> { ObjectStorageInfo(-1) }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(ObjectStorageInfo.serializer(), "{\"length\":-1}")
        }
    }
}

package bosca.storage.service

import bosca.serialization.OffsetDateTimeSerializer
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectStorageEntryTest {
    @Test
    fun `entries preserve literal keys and existing metadata through explicit serialization`() {
        val json = Json { serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) } }
        val entry = ObjectStorageEntry("assets/🖼 +%/file", ObjectStorageInfo(
            Long.MAX_VALUE, "\"opaque-3\"", "generation", OffsetDateTime.parse("2026-10-17T00:00:00.123456789Z"),
        ))
        assertEquals(entry, json.decodeFromString(ObjectStorageEntry.serializer(),
            json.encodeToString(ObjectStorageEntry.serializer(), entry)))
    }

    @Test
    fun `keys are literal while an absent key is invalid`() {
        assertEquals(" ", ObjectStorageEntry(" ", ObjectStorageInfo(1)).key)
        assertFailsWith<IllegalArgumentException> { ObjectStorageEntry("", ObjectStorageInfo(1)) }
    }
}

package bosca.backup

import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import bosca.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectBackupCaptureInventoryTest {
    private val json = Json { serializersModule = SerializersModule { contextual(OffsetDateTimeSerializer()) } }
    private val entry = ObjectStorageEntry("metadata/item/content", ObjectStorageInfo(4, version = "provider-v1"))
    private val inventory = ObjectBackupCaptureInventory(
        "selected", "source", "content", "2026-10-18T00:00:00Z", mapOf("content" to listOf(entry)),
    )

    @Test
    fun `selected provider inventory round trips with an explicit serializer`() {
        val encoded = json.encodeToString(ObjectBackupCaptureInventory.serializer(), inventory)
        val decoded = json.decodeFromString(ObjectBackupCaptureInventory.serializer(), encoded)
        decoded.validate()
        assertEquals(inventory, decoded)
    }

    @Test
    fun `identity source scope and creation time are required`() {
        for (invalid in listOf(inventory.copy(id = " "), inventory.copy(sourceId = ""),
            inventory.copy(scope = ""), inventory.copy(created = "yesterday"))) {
            assertFailsWith<IllegalArgumentException> { invalid.validate() }
        }
    }

    @Test
    fun `logical store and portable object keys are required`() {
        assertFailsWith<IllegalArgumentException> { inventory.copy(stores = mapOf(" " to listOf(entry))).validate() }
        for (key in listOf(" ", "/asset", "a//b", "a/../b", "a/./b", "asset/")) {
            assertFailsWith<IllegalArgumentException> {
                inventory.copy(stores = mapOf("content" to listOf(entry.copy(key = key)))).validate()
            }
        }
    }

    @Test
    fun `selected objects without a provider version need source change metadata`() {
        for (version in listOf(null, "", " ", "null")) {
            assertFailsWith<IllegalArgumentException> {
                inventory.copy(stores = mapOf("content" to listOf(entry.copy(info = entry.info.copy(version = version))))).validate()
            }
        }
    }

    @Test
    fun `unversioned selections round trip using ordinary object change metadata`() {
        for (info in listOf(
            ObjectStorageInfo(4, etag = "opaque-tag"),
            ObjectStorageInfo(4, modified = OffsetDateTime.parse("2026-10-20T00:00:00Z")),
            ObjectStorageInfo(4, etag = "opaque-tag", version = "null"),
        )) {
            val selected = inventory.copy(stores = mapOf("content" to listOf(entry.copy(info = info))))
            val decoded = json.decodeFromString(ObjectBackupCaptureInventory.serializer(),
                json.encodeToString(ObjectBackupCaptureInventory.serializer(), selected))
            decoded.validate()
            assertEquals(selected, decoded)
        }
    }

    @Test
    fun `a current-object inventory rejects repeated keys even with different provider observations`() {
        for (other in listOf(entry, entry.copy(info = entry.info.copy(version = "provider-v2")))) {
            assertFailsWith<IllegalArgumentException> {
                inventory.copy(stores = mapOf("content" to listOf(entry, other))).validate()
            }
        }
        inventory.copy(stores = mapOf("content" to listOf(entry), "artifacts" to listOf(entry))).validate()
    }

    @Test
    fun `explicitly empty source scopes are valid`() {
        inventory.copy(stores = emptyMap()).validate()
        inventory.copy(stores = mapOf("content" to emptyList())).validate()
    }
}

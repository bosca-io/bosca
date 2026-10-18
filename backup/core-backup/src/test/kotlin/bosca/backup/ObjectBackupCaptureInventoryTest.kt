package bosca.backup

import bosca.storage.service.ObjectStorageEntry
import bosca.storage.service.ObjectStorageInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObjectBackupCaptureInventoryTest {
    private val entry = ObjectStorageEntry("metadata/item/content", ObjectStorageInfo(4, version = "provider-v1"))
    private val inventory = ObjectBackupCaptureInventory(
        "selected", "source", "content", "2026-10-18T00:00:00Z", mapOf("content" to listOf(entry)),
    )

    @Test
    fun `selected provider inventory round trips with an explicit serializer`() {
        val encoded = Json.encodeToString(ObjectBackupCaptureInventory.serializer(), inventory)
        val decoded = Json.decodeFromString(ObjectBackupCaptureInventory.serializer(), encoded)
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
    fun `every selected object needs a retained provider version`() {
        for (version in listOf(null, "", " ", "null")) {
            assertFailsWith<IllegalArgumentException> {
                inventory.copy(stores = mapOf("content" to listOf(entry.copy(info = entry.info.copy(version = version))))).validate()
            }
        }
    }

    @Test
    fun `duplicate selected revisions are rejected but distinct stores and historical versions are allowed`() {
        assertFailsWith<IllegalArgumentException> {
            inventory.copy(stores = mapOf("content" to listOf(entry, entry))).validate()
        }
        inventory.copy(stores = mapOf("content" to listOf(entry,
            entry.copy(info = entry.info.copy(version = "provider-v2"))), "artifacts" to listOf(entry))).validate()
    }

    @Test
    fun `explicitly empty source scopes are valid`() {
        inventory.copy(stores = emptyMap()).validate()
        inventory.copy(stores = mapOf("content" to emptyList())).validate()
    }
}

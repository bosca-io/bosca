package bosca.content.search

import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageSystemExtTest {

    private fun storageSystem(configuration: kotlinx.serialization.json.JsonElement) = StorageSystem(
        name = "test",
        description = "test",
        type = StorageSystemType.SEARCH,
        configuration = configuration,
    )

    @Test
    fun `isContentIndex returns true when contentIndex is absent`() {
        val system = storageSystem(JsonObject(mapOf("indexName" to JsonPrimitive("default"))))
        assertTrue(system.isContentIndex)
    }

    @Test
    fun `isContentIndex returns true when contentIndex is true`() {
        val system = storageSystem(
            JsonObject(
                mapOf(
                    "indexName" to JsonPrimitive("default"),
                    "contentIndex" to JsonPrimitive(true),
                )
            )
        )
        assertTrue(system.isContentIndex)
    }

    @Test
    fun `isContentIndex returns false when contentIndex is false`() {
        val system = storageSystem(
            JsonObject(
                mapOf(
                    "indexName" to JsonPrimitive("api-documentation"),
                    "contentIndex" to JsonPrimitive(false),
                )
            )
        )
        assertFalse(system.isContentIndex)
    }

    @Test
    fun `isContentIndex returns true when configuration is JsonNull`() {
        val system = storageSystem(JsonNull)
        assertTrue(system.isContentIndex)
    }
}

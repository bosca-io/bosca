@file:OptIn(ExperimentalUuidApi::class)

package bosca.storage.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class StorageSystemInputTest {

    @Test
    fun `StorageSystemInput stores all fields`() {
        val config = JsonObject(mapOf("url" to JsonPrimitive("http://meili:7700")))
        val modelId = UUID.random()
        val modelConfig = JsonObject(mapOf("index" to JsonPrimitive("documents")))
        val modelInput = StorageSystemModelInput(modelId = modelId, configuration = modelConfig)
        val input = StorageSystemInput(
            name = "Meilisearch",
            description = "Search engine",
            type = StorageSystemType.SEARCH,
            configuration = config,
            models = listOf(modelInput)
        )
        assertEquals("Meilisearch", input.name)
        assertEquals("Search engine", input.description)
        assertEquals(StorageSystemType.SEARCH, input.type)
        assertEquals(config, input.configuration)
        assertEquals(1, input.models.size)
        assertEquals(modelId, input.models.first().modelId)
    }

    @Test
    fun `StorageSystemInput with empty models list`() {
        val config = JsonObject(mapOf("key" to JsonPrimitive("val")))
        val input = StorageSystemInput(
            name = "Vector DB",
            description = "Vector store",
            type = StorageSystemType.VECTOR,
            configuration = config,
            models = emptyList()
        )
        assertTrue(input.models.isEmpty())
    }

    @Test
    fun `StorageSystemModelInput stores modelId and configuration`() {
        val modelId = UUID.random()
        val config = JsonObject(mapOf("dim" to JsonPrimitive(768)))
        val modelInput = StorageSystemModelInput(modelId = modelId, configuration = config)
        assertEquals(modelId, modelInput.modelId)
        assertEquals(config, modelInput.configuration)
    }

    @Test
    fun `StorageSystemModelInput equality`() {
        val id = UUID.random()
        val config = JsonObject(emptyMap())
        val a = StorageSystemModelInput(modelId = id, configuration = config)
        val b = StorageSystemModelInput(modelId = id, configuration = config)
        assertEquals(a, b)
    }
}

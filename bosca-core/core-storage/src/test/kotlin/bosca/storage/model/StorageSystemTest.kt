package bosca.storage.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class StorageSystemTest {

    @Test
    fun `StorageSystem stores all fields`() {
        val id = Uuid.random()
        val config = JsonObject(mapOf("endpoint" to JsonPrimitive("https://storage.example.com")))
        val system = StorageSystem(
            id = id,
            name = "Primary Storage",
            description = "Main storage system",
            type = StorageSystemType.SEARCH,
            configuration = config
        )
        assertEquals(id, system.id)
        assertEquals("Primary Storage", system.name)
        assertEquals("Main storage system", system.description)
        assertEquals(StorageSystemType.SEARCH, system.type)
        assertEquals(config, system.configuration)
    }

    @Test
    fun `StorageSystem default id is NIL`() {
        val system = StorageSystem(
            name = "Test",
            description = "Test",
            type = StorageSystemType.SUPPLEMENTARY,
            configuration = JsonObject(emptyMap())
        )
        assertEquals(Uuid.NIL, system.id)
    }

    @Test
    fun `StorageSystemModel stores fields`() {
        val systemId = Uuid.random()
        val modelId = Uuid.random()
        val config = JsonObject(mapOf("dim" to JsonPrimitive(1536)))
        val model = StorageSystemModel(
            systemId = systemId,
            modelId = modelId,
            configuration = config
        )
        assertEquals(systemId, model.systemId)
        assertEquals(modelId, model.modelId)
        assertEquals(config, model.configuration)
    }

    @Test
    fun `StorageSystemInput stores fields`() {
        val modelId = Uuid.random()
        val config = JsonObject(emptyMap())
        val modelConfig = JsonObject(emptyMap())
        val input = StorageSystemInput(
            name = "New Storage",
            description = "A new storage",
            type = StorageSystemType.VECTOR,
            configuration = config,
            models = listOf(StorageSystemModelInput(modelId = modelId, configuration = modelConfig))
        )
        assertEquals("New Storage", input.name)
        assertEquals("A new storage", input.description)
        assertEquals(StorageSystemType.VECTOR, input.type)
        assertEquals(1, input.models.size)
        assertEquals(modelId, input.models[0].modelId)
    }

    @Test
    fun `StorageSystemInput with empty models list`() {
        val input = StorageSystemInput(
            name = "Empty",
            description = "No models",
            type = StorageSystemType.SEARCH,
            configuration = JsonObject(emptyMap()),
            models = emptyList()
        )
        assertEquals(0, input.models.size)
    }
}

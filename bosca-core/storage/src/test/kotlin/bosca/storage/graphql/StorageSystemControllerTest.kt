package bosca.storage.graphql

import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StorageSystemControllerTest {

    private val storageSystemService = mockk<StorageSystemService>()
    private val controller = StorageSystemController(storageSystemService)

    private val systemId = Uuid.random()
    private val configuration = JsonObject(mapOf("key" to JsonPrimitive("value")))

    private val system = StorageSystem(
        id = systemId,
        name = "Test Storage",
        description = "A test storage system",
        type = StorageSystemType.SEARCH,
        configuration = configuration
    )

    @Test
    fun `id returns the storage system id`() {
        assertEquals(systemId, controller.id(system))
    }

    @Test
    fun `name returns the storage system name`() {
        assertEquals("Test Storage", controller.name(system))
    }

    @Test
    fun `description returns the storage system description`() {
        assertEquals("A test storage system", controller.description(system))
    }

    @Test
    fun `type returns the storage system type`() {
        assertEquals(StorageSystemType.SEARCH, controller.type(system))
    }

    @Test
    fun `configuration returns the storage system configuration`() {
        assertEquals(configuration, controller.configuration(system))
    }

    @Test
    fun `models delegates to service`() = runTest {
        val modelId = Uuid.random()
        val model = bosca.storage.model.StorageSystemModel(
            systemId = systemId,
            modelId = modelId,
            configuration = JsonObject(emptyMap())
        )
        coEvery { storageSystemService.getModels(systemId) } returns listOf(model)

        val result = controller.models(system)
        assertEquals(1, result.size)
        assertEquals(systemId, result[0].systemId)
        assertEquals(modelId, result[0].modelId)
    }

    @Test
    fun `models returns empty list when none exist`() = runTest {
        coEvery { storageSystemService.getModels(systemId) } returns emptyList()
        assertTrue(controller.models(system).isEmpty())
    }
}

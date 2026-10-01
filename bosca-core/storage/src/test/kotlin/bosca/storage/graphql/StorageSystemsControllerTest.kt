package bosca.storage.graphql

import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StorageSystemsControllerTest {

    private val storageSystemService = mockk<StorageSystemService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = StorageSystemsController(storageSystemService, groupEvaluator)
    private val authContext = mockk<AuthenticationContext>()

    @Test
    fun `all verifies admin group and returns all systems`() = runTest {
        coEvery { groupEvaluator.verifyHasAdminGroup(authContext) } just runs
        val systems = listOf(
            StorageSystem(
                id = Uuid.random(),
                name = "System A",
                description = "Description A",
                type = StorageSystemType.SEARCH,
                configuration = JsonObject(emptyMap())
            )
        )
        coEvery { storageSystemService.getAll() } returns systems

        val result = controller.all(authContext)
        assertEquals(1, result.size)
        assertEquals("System A", result[0].name)
        coVerify { groupEvaluator.verifyHasAdminGroup(authContext) }
    }

    @Test
    fun `all returns empty list when no systems exist`() = runTest {
        coEvery { groupEvaluator.verifyHasAdminGroup(authContext) } just runs
        coEvery { storageSystemService.getAll() } returns emptyList()

        assertTrue(controller.all(authContext).isEmpty())
    }

    @Test
    fun `storageSystem verifies admin group and returns system by id`() = runTest {
        val systemId = Uuid.random()
        coEvery { groupEvaluator.verifyHasAdminGroup(authContext) } just runs
        val system = StorageSystem(
            id = systemId,
            name = "System B",
            description = "Description B",
            type = StorageSystemType.SUPPLEMENTARY,
            configuration = JsonObject(emptyMap())
        )
        coEvery { storageSystemService.get(systemId) } returns system

        val result = controller.storageSystem(authContext, systemId)
        assertEquals("System B", result?.name)
        coVerify { groupEvaluator.verifyHasAdminGroup(authContext) }
    }

    @Test
    fun `storageSystem returns null when not found`() = runTest {
        val systemId = Uuid.random()
        coEvery { groupEvaluator.verifyHasAdminGroup(authContext) } just runs
        coEvery { storageSystemService.get(systemId) } returns null

        assertNull(controller.storageSystem(authContext, systemId))
    }
}

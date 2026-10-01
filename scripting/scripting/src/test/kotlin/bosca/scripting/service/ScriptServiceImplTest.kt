@file:OptIn(ExperimentalUuidApi::class)

package bosca.scripting.service

import bosca.cache.CacheManager
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.scripting.engine.Engine
import bosca.scripting.model.Script
import bosca.scripting.model.ScriptInput
import bosca.scripting.model.ScriptType
import bosca.scripting.repository.ScriptPermissionRepository
import bosca.scripting.repository.ScriptRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(InternalDI::class)
class ScriptServiceImplTest {

    private val repository = mockk<ScriptRepository>()
    private val permissionRepository = mockk<ScriptPermissionRepository>(relaxed = true)
    private val engine = mockk<Engine>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private lateinit var service: ScriptServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        service = ScriptServiceImpl(repository, permissionRepository, engine, pubSubService)
    }

    private val testScript = Script(
        id = Uuid.random(),
        key = "test-key",
        name = "Test Script",
        description = "A test",
        type = ScriptType.GENERAL,
        source = "\"hello\"",
        version = 1,
        enabled = true
    )

    @Test
    fun `getAll delegates to repository`() = runTest {
        coEvery { repository.getAll() } returns listOf(testScript)
        val result = service.getAll()
        assertEquals(listOf(testScript), result)
        coVerify { repository.getAll() }
    }

    @Test
    fun `getByType delegates with type name`() = runTest {
        coEvery { repository.getByType(ScriptType.TRIGGER) } returns listOf(testScript)
        val result = service.getByType(ScriptType.TRIGGER)
        assertEquals(listOf(testScript), result)
        coVerify { repository.getByType(ScriptType.TRIGGER) }
    }

    @Test
    fun `get delegates to repository getById`() = runTest {
        coEvery { repository.getById(testScript.id) } returns testScript
        val result = service.get(testScript.id)
        assertEquals(testScript, result)
    }

    @Test
    fun `getByKey delegates to repository`() = runTest {
        coEvery { repository.getByKey("test-key") } returns testScript
        val result = service.getByKey("test-key")
        assertEquals(testScript, result)
    }

    @Test
    fun `add creates script from input and delegates`() = runTest {
        val input = ScriptInput(
            key = "new-key",
            name = "New Script",
            description = "desc",
            type = ScriptType.TOOL,
            source = "42"
        )
        val expected = testScript.copy(key = "new-key", name = "New Script")
        coEvery { repository.add(any()) } returns expected
        val result = service.add(input)
        assertEquals(expected, result)
        coVerify { repository.add(match { it.key == "new-key" && it.name == "New Script" && it.type == ScriptType.TOOL }) }
    }

    @Test
    fun `edit invalidates cache and updates`() = runTest {
        val input = ScriptInput(
            key = "updated-key",
            name = "Updated",
            source = "new source"
        )
        val updated = testScript.copy(key = "updated-key", name = "Updated", source = "new source")
        coEvery { repository.getById(testScript.id) } returns testScript
        coEvery { repository.update(any()) } returns updated

        val result = service.edit(testScript.id, input)
        assertEquals(updated, result)
        verify { engine.invalidate(testScript.key, testScript.version) }
        coVerify { repository.update(any()) }
    }

    @Test
    fun `edit throws NoSuchElementException for missing script`() = runTest {
        val id = Uuid.random()
        coEvery { repository.getById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            service.edit(id, ScriptInput(key = "k", name = "n", source = "s"))
        }
    }

    @Test
    fun `delete hard-deletes non-EPHEMERAL scripts`() = runTest {
        coEvery { repository.getById(testScript.id) } returns testScript
        coEvery { repository.deleteById(testScript.id) } returns Unit

        service.delete(testScript.id)
        verify { engine.invalidate(testScript.key, testScript.version) }
        coVerify { repository.deleteById(testScript.id) }
        coVerify(exactly = 0) { repository.softDelete(any()) }
    }

    @Test
    fun `delete soft-deletes EPHEMERAL scripts`() = runTest {
        val ephemeral = testScript.copy(type = ScriptType.EPHEMERAL)
        coEvery { repository.getById(ephemeral.id) } returns ephemeral
        coEvery { repository.softDelete(ephemeral.id) } returns ephemeral

        service.delete(ephemeral.id)
        verify { engine.invalidate(ephemeral.key, ephemeral.version) }
        coVerify { repository.softDelete(ephemeral.id) }
        coVerify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun `delete skips invalidation when script not found`() = runTest {
        val id = Uuid.random()
        coEvery { repository.getById(id) } returns null
        coEvery { repository.deleteById(id) } returns Unit

        service.delete(id)
        verify(exactly = 0) { engine.invalidate(any(), any()) }
        coVerify { repository.deleteById(id) }
    }

    @Test
    fun `enable delegates to repository`() = runTest {
        val enabled = testScript.copy(enabled = true)
        coEvery { repository.setEnabled(testScript.id, true) } returns enabled
        val result = service.enable(testScript.id)
        assertEquals(enabled, result)
    }

    @Test
    fun `disable invalidates cache and delegates`() = runTest {
        val disabled = testScript.copy(enabled = false)
        coEvery { repository.getById(testScript.id) } returns testScript
        coEvery { repository.setEnabled(testScript.id, false) } returns disabled

        val result = service.disable(testScript.id)
        assertEquals(disabled, result)
        verify { engine.invalidate(testScript.key, testScript.version) }
    }
}

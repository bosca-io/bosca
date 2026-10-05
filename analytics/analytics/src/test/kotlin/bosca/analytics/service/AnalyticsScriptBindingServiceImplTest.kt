@file:OptIn(ExperimentalUuidApi::class)

package bosca.analytics.service

import bosca.analytics.model.AnalyticsScriptBinding
import bosca.analytics.repository.AnalyticsScriptBindingRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AnalyticsScriptBindingServiceImplTest {

    private val repository = mockk<AnalyticsScriptBindingRepository>()
    private val service = AnalyticsScriptBindingServiceImpl(repository)

    private fun binding(
        id: Uuid = Uuid.random(),
        scriptId: Uuid = Uuid.random(),
        transform: Boolean = true,
        enabled: Boolean = true,
        ordinal: Int = 0,
    ) = AnalyticsScriptBinding(
        id = id,
        scriptId = scriptId,
        transform = transform,
        enabled = enabled,
        ordinal = ordinal,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    @Test
    fun `list delegates to the repository`() = runTest {
        val all = listOf(binding(), binding())
        coEvery { repository.getAll() } returns all
        assertSame(all, service.list())
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val b = binding()
        coEvery { repository.getById(b.id) } returns b
        assertSame(b, service.get(b.id))
    }

    @Test
    fun `add inserts with a generated id and returns the row`() = runTest {
        val scriptId = Uuid.random()
        val created = binding(scriptId = scriptId, transform = false, ordinal = 3)
        coEvery { repository.insert(any(), scriptId, false, true, 3) } returns created

        val result = service.add(scriptId, transform = false, enabled = true, ordinal = 3)

        assertSame(created, result)
        coVerify(exactly = 1) { repository.insert(any(), scriptId, false, true, 3) }
    }

    @Test
    fun `add defaults transform to true`() = runTest {
        val scriptId = Uuid.random()
        coEvery { repository.insert(any(), scriptId, true, true, 0) } returns binding(scriptId = scriptId)
        service.add(scriptId)
        coVerify(exactly = 1) { repository.insert(any(), scriptId, true, true, 0) }
    }

    @Test
    fun `add throws when the repository returns null`() = runTest {
        val scriptId = Uuid.random()
        coEvery { repository.insert(any(), scriptId, true, true, 0) } returns null
        assertFailsWith<IllegalStateException> { service.add(scriptId) }
    }

    @Test
    fun `update returns the updated row`() = runTest {
        val id = Uuid.random()
        val scriptId = Uuid.random()
        val updated = binding(id = id, scriptId = scriptId, transform = false, enabled = false)
        coEvery { repository.update(id, scriptId, false, false, 1) } returns updated

        val result = service.update(id, scriptId, transform = false, enabled = false, ordinal = 1)

        assertSame(updated, result)
    }

    @Test
    fun `update throws when the binding does not exist`() = runTest {
        val id = Uuid.random()
        val scriptId = Uuid.random()
        coEvery { repository.update(id, scriptId, true, true, 0) } returns null
        assertFailsWith<IllegalStateException> { service.update(id, scriptId) }
    }

    @Test
    fun `delete delegates to the repository`() = runTest {
        val id = Uuid.random()
        coEvery { repository.delete(id) } just Runs
        service.delete(id)
        coVerify(exactly = 1) { repository.delete(id) }
    }

    @Test
    fun `enabledBindings caches within the TTL`() = runTest {
        val enabled = listOf(binding(enabled = true))
        coEvery { repository.getEnabled() } returns enabled

        assertSame(enabled, service.enabledBindings())
        assertSame(enabled, service.enabledBindings())

        coVerify(exactly = 1) { repository.getEnabled() }
    }

    @Test
    fun `enabledBindings reloads after the TTL expires`() = runTest {
        val first = listOf(binding(enabled = true))
        val second = listOf(binding(enabled = true), binding(enabled = true))
        coEvery { repository.getEnabled() } returnsMany listOf(first, second)

        assertSame(first, service.enabledBindings())
        AnalyticsScriptBindingServiceImpl::class.java.getDeclaredField("enabledCache").apply {
            isAccessible = true
            set(service, first to 0L)
        }
        assertSame(second, service.enabledBindings())

        coVerify(exactly = 2) { repository.getEnabled() }
    }

    @Test
    fun `a write invalidates the enabledBindings cache`() = runTest {
        val first = listOf(binding(enabled = true))
        val second = listOf(binding(enabled = true), binding(enabled = true))
        coEvery { repository.getEnabled() } returnsMany listOf(first, second)
        coEvery { repository.delete(any()) } just Runs

        assertSame(first, service.enabledBindings())
        service.delete(Uuid.random())
        assertSame(second, service.enabledBindings())

        coVerify(exactly = 2) { repository.getEnabled() }
    }
}

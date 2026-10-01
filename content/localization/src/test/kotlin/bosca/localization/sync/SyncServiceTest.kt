@file:OptIn(ExperimentalUuidApi::class)

package bosca.localization.sync

import bosca.localization.model.LocalizationSyncConfigInput
import bosca.localization.model.LocalizationSyncState
import bosca.localization.repository.LocalizationSyncStateRepository
import bosca.localization.service.LocalizationSyncServiceImpl
import bosca.localization.sync.SyncProviders
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class SyncServiceTest {

    private val repo = mockk<LocalizationSyncStateRepository>(relaxed = true)
    private val crowdin = CrowdinSyncProvider()
    private val service = LocalizationSyncServiceImpl(repo, SyncProviders(listOf(crowdin)))

    @Test
    fun `configureSyncProvider rejects an unregistered provider name`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.configureSyncProvider(LocalizationSyncConfigInput(projectId = UUID.random(), provider = "unknown"))
        }
    }

    @Test
    fun `syncToProvider returns an error result when no binding exists`() = runTest {
        coEvery { repo.getByProjectId(any()) } returns null
        val result = service.syncToProvider(UUID.random())
        assertTrue(result.errors.isNotEmpty(), "missing binding should produce an error")
    }

    @Test
    fun `syncToProvider returns an error result when the provider name is unknown`() = runTest {
        val state = LocalizationSyncState(projectId = UUID.random(), provider = "non-existent")
        coEvery { repo.getByProjectId(any()) } returns state
        val result = service.syncToProvider(state.projectId)
        assertTrue(result.errors.any { it.contains("not registered") })
    }

    @Test
    fun `syncToProvider does not mark synced when the provider returns errors`() = runTest {
        val pid = UUID.random()
        val state = LocalizationSyncState(projectId = pid, provider = "crowdin")
        coEvery { repo.getByProjectId(pid) } returns state
        val result = service.syncToProvider(pid)
        assertTrue(result.errors.isNotEmpty(), "stubbed crowdin returns not-implemented")
        coVerify(exactly = 0) { repo.markSynced(pid) }
    }

    @Test
    fun `syncFromProvider does not mark synced when the provider returns errors`() = runTest {
        val pid = UUID.random()
        val state = LocalizationSyncState(projectId = pid, provider = "crowdin")
        coEvery { repo.getByProjectId(pid) } returns state
        val result = service.syncFromProvider(pid)
        assertTrue(result.errors.isNotEmpty())
        coVerify(exactly = 0) { repo.markSynced(pid) }
    }
}

class CrowdinSyncProviderTest {

    @Test
    fun `stub returns a clear not-implemented message in both directions`() = runTest {
        val provider = CrowdinSyncProvider()
        assertEquals("crowdin", provider.provider)
        val state = LocalizationSyncState(projectId = UUID.random(), provider = "crowdin")
        val push = provider.syncToProvider(UUID.random(), state)
        val pull = provider.syncFromProvider(UUID.random(), state)
        assertTrue(push.errors.single().contains("not yet implemented"))
        assertTrue(pull.errors.single().contains("not yet implemented"))
        assertEquals(0, push.stringsAdded)
        assertEquals(0, pull.translationsAdded)
    }
}

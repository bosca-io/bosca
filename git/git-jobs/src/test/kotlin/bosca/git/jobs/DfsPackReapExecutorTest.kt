@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.jobs

import bosca.di.provides
import bosca.git.service.RepositoryLifecycleService
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class DfsPackReapExecutorTest {

    private val lifecycleService = mockk<RepositoryLifecycleService>(relaxed = true)

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `execute delegates to reapDeletedPacks`() = runTest {
        provides<RepositoryLifecycleService>(singleton = true) { lifecycleService }

        DfsPackReapExecutor().execute()

        coVerify { lifecycleService.reapDeletedPacks() }
    }

    @Test
    fun `job payload serializes round-trip`() {
        val encoded = Json.encodeToString(DfsPackReapJob.serializer(), DfsPackReapJob())
        assertNotNull(Json.decodeFromString(DfsPackReapJob.serializer(), encoded))
    }
}

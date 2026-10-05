@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.profile.profile.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.profile.service.ProfileCleanupHandler
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProfileCleanupExecutorTest {

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json> { Json }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `cleanup job invokes registered deletion handlers`() = runTest {
        val profileId = UUID.random()
        val principalId = UUID.random()
        val handler = mockk<ProfileCleanupHandler>(relaxed = true)
        provides<ProfileCleanupHandler>(name = "test-profile-cleanup-handler") { handler }
        val definition = ProfileCleanupJob(profileId, principalId)
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(ProfileCleanupJob.serializer(), definition),
            executor = ProfileCleanupExecutor::class,
        )

        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            ProfileCleanupExecutor().execute()
        }

        coVerify(exactly = 1) { handler.onProfileCleanup(profileId, principalId) }
    }

    @Test
    fun `cleanup job attempts every handler before retrying a failure`() = runTest {
        val profileId = UUID.random()
        val principalId = UUID.random()
        val failingHandler = mockk<ProfileCleanupHandler>()
        val succeedingHandler = mockk<ProfileCleanupHandler>(relaxed = true)
        coEvery { failingHandler.onProfileCleanup(profileId, principalId) } throws
            IllegalStateException("cleanup failed")
        provides<ProfileCleanupHandler> { failingHandler }
        provides<ProfileCleanupHandler>(name = "later-profile-cleanup-handler") { succeedingHandler }
        val definition = ProfileCleanupJob(profileId, principalId)
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(ProfileCleanupJob.serializer(), definition),
            executor = ProfileCleanupExecutor::class,
        )

        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            assertFailsWith<IllegalStateException> {
                ProfileCleanupExecutor().execute()
            }
        }

        coVerify(exactly = 1) { failingHandler.onProfileCleanup(profileId, principalId) }
        coVerify(exactly = 1) { succeedingHandler.onProfileCleanup(profileId, principalId) }
    }
}

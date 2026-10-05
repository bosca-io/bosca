@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.profile.profile.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.lock.DistributedLockFactory
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.transformations.Transformation
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ProfileIndexExecutorTest {

    private val transform = mockk<Transformation<IndexStorageSystem, Profile, JsonElement>>()
    private val distributedLock = mockk<DistributedLockFactory>()
    private val profileService = mockk<ProfileService>()
    private val searchService = mockk<SearchService>(relaxed = true)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<ProfileService> { profileService }
        provides<SearchService> { searchService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `targeted public index job deletes a non-searchable profile document`() = runTest {
        val profileId = UUID.random()
        val storage = IndexStorageSystem(UUID.random(), SearchDocumentPipeline.PROFILE_INDEX)
        coEvery { profileService.getAllByIds(listOf(profileId)) } returns listOf(
            Profile(
                id = profileId,
                type = ProfileType.GENERIC,
                name = "Private profile",
                visibility = ProfileVisibility.PUBLIC,
                searchable = false,
            ),
        )
        val definition = ProfileIndexJob(storage = storage, id = profileId)
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(ProfileIndexJob.serializer(), definition),
            executor = ProfileIndexExecutor::class,
        )
        val application = BoscaApplication(ApplicationConfig.load("{}".byteInputStream()))

        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            ProfileIndexExecutor(transform, distributedLock, application).execute()
        }

        coVerify(exactly = 1) { searchService.delete(storage, profileId.toString()) }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
        coVerify(exactly = 0) { searchService.index(storage, any<JsonElement>()) }
    }
}

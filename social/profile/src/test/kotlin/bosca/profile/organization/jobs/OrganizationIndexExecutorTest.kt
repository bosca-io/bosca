@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.profile.organization.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.search.IndexStorageSystem
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.search.service.SearchService
import bosca.serialization.UUID
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
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class OrganizationIndexExecutorTest {

    private val transform = mockk<Transformation<IndexStorageSystem, Profile, JsonElement>>()
    private val organizationService = mockk<OrganizationService>()
    private val profileService = mockk<ProfileService>()
    private val searchService = mockk<SearchService>(relaxed = true)

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<ProfileService> { profileService }
        provides<SearchService> { searchService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `targeted public index job deletes a non-searchable organization document`() = runTest {
        val organizationId = UUID.random()
        val profileId = UUID.random()
        val storage = IndexStorageSystem(UUID.random(), SearchDocumentPipeline.PROFILE_INDEX)
        coEvery { organizationService.getOrganization(organizationId) } returns Organization(
            id = organizationId,
            name = "Private organization",
            attributes = buildJsonObject {},
            systemAttributes = buildJsonObject {},
            visibility = ProfileVisibility.PUBLIC,
            profileId = profileId,
        )
        coEvery { profileService.getById(profileId) } returns Profile(
            id = profileId,
            type = ProfileType.ORGANIZATION,
            name = "Private organization",
            visibility = ProfileVisibility.PUBLIC,
            searchable = false,
        )
        val definition = OrganizationIndexJob(storage = storage, id = organizationId)
        val job = InternalJobConstructor(
            definition = Json.encodeToJsonElement(OrganizationIndexJob.serializer(), definition),
            executor = OrganizationIndexExecutor::class,
        )

        withContext(mockk<JobQueue>(relaxed = true).asCoroutineContext(job)) {
            OrganizationIndexExecutor(transform, organizationService).execute()
        }

        coVerify(exactly = 1) { searchService.delete(storage, profileId.toString()) }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
        coVerify(exactly = 0) { searchService.index(storage, any<JsonElement>()) }
    }
}

@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package bosca.hubspot

import bosca.di.ObjectProvider
import bosca.hubspot.client.HubSpot
import bosca.hubspot.installer.HubSpotPipelinesInstaller
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * [syncProfileToHubSpot] selects and runs the right seeded sync pipeline for a profile: the contact
 * pipeline for a person, the company pipeline for any other type. It no-ops when HubSpot isn't
 * configured (the provider is absent) and errors when the pipeline isn't installed.
 */
class HubSpotProfileSyncTest {

    private fun profile(type: ProfileType) =
        Profile(id = Uuid.random(), type = type, name = "p", visibility = ProfileVisibility.PUBLIC)

    private fun present(): ObjectProvider<HubSpot> = mockk { every { exists } returns true }

    @Test
    fun `a person syncs through the contact pipeline`() = runTest {
        val pipeline = mockk<Pipeline>(relaxed = true)
        val keys = slot<String>()
        val pipelines = mockk<PipelineService> {
            coEvery { getByKey(capture(keys)) } returns pipeline
            coEvery { run(any(), any()) } returns null
        }

        syncProfileToHubSpot(profile(ProfileType.GENERIC), present(), pipelines)

        assertEquals(HubSpotPipelinesInstaller.SYNC_CONTACT_KEY, keys.captured)
        coVerify(exactly = 1) { pipelines.run(pipeline, any<PipelineValue>()) }
    }

    @Test
    fun `an organization syncs through the company pipeline`() = runTest {
        val pipeline = mockk<Pipeline>(relaxed = true)
        val keys = slot<String>()
        val pipelines = mockk<PipelineService> {
            coEvery { getByKey(capture(keys)) } returns pipeline
            coEvery { run(any(), any()) } returns null
        }

        syncProfileToHubSpot(profile(ProfileType.ORGANIZATION), present(), pipelines)

        assertEquals(HubSpotPipelinesInstaller.SYNC_COMPANY_KEY, keys.captured)
        coVerify(exactly = 1) { pipelines.run(pipeline, any<PipelineValue>()) }
    }

    @Test
    fun `no-ops when HubSpot is not configured`() = runTest {
        val absent: ObjectProvider<HubSpot> = mockk { every { exists } returns false }
        val pipelines = mockk<PipelineService>()

        syncProfileToHubSpot(profile(ProfileType.GENERIC), absent, pipelines)

        coVerify(exactly = 0) { pipelines.getByKey(any()) }
        coVerify(exactly = 0) { pipelines.run(any(), any()) }
    }

    @Test
    fun `fails when the sync pipeline is not installed`() = runTest {
        val pipelines = mockk<PipelineService> {
            coEvery { getByKey(any()) } returns null
        }

        val error = assertFailsWith<IllegalStateException> {
            syncProfileToHubSpot(profile(ProfileType.GENERIC), present(), pipelines)
        }
        assertEquals(true, error.message?.contains("is not installed"))
    }

    @Test
    fun `the id overload resolves the profile then syncs`() = runTest {
        val person = profile(ProfileType.GENERIC)
        val profileService = mockk<bosca.profile.profile.service.ProfileService> {
            coEvery { getById(person.id) } returns person
        }
        val pipeline = mockk<Pipeline>(relaxed = true)
        val keys = slot<String>()
        val pipelines = mockk<PipelineService> {
            coEvery { getByKey(capture(keys)) } returns pipeline
            coEvery { run(any(), any()) } returns null
        }

        syncProfileToHubSpot(person.id, present(), profileService, pipelines)

        assertEquals(HubSpotPipelinesInstaller.SYNC_CONTACT_KEY, keys.captured)
        coVerify(exactly = 1) { pipelines.run(pipeline, any<PipelineValue>()) }
    }
}

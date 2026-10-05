package bosca.profile.guide.graphql

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideType
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.service.ProfileMarkService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileGuideProgressControllerTest {

    private val guideService = mockk<GuideService>()
    private val markService = mockk<ProfileMarkService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val profilePermissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val profileService = mockk<ProfileService>()
    private val authentication = mockk<AuthenticationContext>()
    private val controller = ProfileGuideProgressController(guideService, markService, metadataService, metadataPermissionEvaluator, profilePermissionEvaluator, profileService)

    private fun createProgress(
        completedStepIds: List<Long> = emptyList(),
        attributes: kotlinx.serialization.json.JsonElement? = JsonObject(emptyMap())
    ): ProfileGuideProgress {
        return ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 1,
            attributes = attributes,
            completedStepIds = completedStepIds
        )
    }

    @Test
    fun `attributes returns progress attributes`() {
        val attrs = buildJsonObject { put("key", "value") }
        val progress = createProgress(attributes = attrs)

        assertEquals(attrs, controller.attributes(progress))
    }

    @Test
    fun `completedStepIds returns the list of completed step ids`() {
        val progress = createProgress(completedStepIds = listOf(1L, 2L, 3L))

        assertEquals(listOf(1L, 2L, 3L), controller.completedStepIds(progress))
    }

    @Test
    fun `completedStepIds returns empty list when no steps completed`() {
        val progress = createProgress()

        assertEquals(emptyList(), controller.completedStepIds(progress))
    }

    @Test
    fun `version returns the progression guide version`() {
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 7,
            attributes = JsonObject(emptyMap()),
        )

        assertEquals(7, controller.version(progress))
    }

    @Test
    fun `guide resolves the progression's exact guide version`() = runTest {
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            version = 7,
            attributes = JsonObject(emptyMap()),
        )
        val metadata = mockk<bosca.content.metadata.model.Metadata>()
        val guide = Guide(
            metadataId = progress.metadataId,
            version = progress.version,
            rrule = null,
            type = GuideType.LINEAR,
            templateMetadataId = null,
            templateMetadataVersion = null,
        )
        coEvery { metadataService.getById(progress.metadataId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        } returns true
        coEvery { guideService.getGuide(progress.metadataId, progress.version) } returns guide

        assertSame(guide, controller.guide(authentication, progress))
        coVerify(exactly = 1) { guideService.getGuide(progress.metadataId, 7) }
    }

    @Test
    fun `guide returns null when metadata permission is denied`() = runTest {
        val progress = createProgress()
        val metadata = mockk<bosca.content.metadata.model.Metadata>()
        coEvery { metadataService.getById(progress.metadataId) } returns metadata
        coEvery {
            metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)
        } returns false

        assertNull(controller.guide(authentication, progress))
        coVerify(exactly = 0) { guideService.getGuide(any(), any()) }
    }

    @Test
    fun `modified returns progress modified timestamp`() {
        val progress = createProgress()

        assertEquals(progress.modified, controller.modified(progress))
    }

    @Test
    fun `started returns progress started timestamp`() {
        val progress = createProgress()

        assertEquals(progress.started, controller.started(progress))
    }

    @Test
    fun `percentage returns 0 when no steps completed`() = runTest {
        val progress = createProgress(completedStepIds = emptyList())

        val result = controller.percentage(progress)

        assertEquals(0.0f, result)
    }

    @Test
    fun `percentage returns 100 when all steps completed`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(1L, 2L)
        )

        coEvery { guideService.getStepCount(metadataId, 1) } returns 2L

        val result = controller.percentage(progress)

        assertEquals(100.0f, result)
    }

    @Test
    fun `percentage returns 50 when half steps completed`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(1L)
        )

        coEvery { guideService.getStepCount(metadataId, 1) } returns 2L

        val result = controller.percentage(progress)

        assertEquals(50.0f, result)
    }

    @Test
    fun `percentage returns 0 when total steps is 0`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(1L)
        )

        coEvery { guideService.getStepCount(metadataId, 1) } returns 0L

        val result = controller.percentage(progress)

        assertEquals(0.0f, result)
    }

    private fun mockProfileAllowed(profileId: UUID): Profile {
        val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.PUBLIC)
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns true
        return profile
    }

    private fun mockProfileDenied(profileId: UUID): Profile {
        val profile = Profile(id = profileId, type = ProfileType.GENERIC, name = "test", visibility = ProfileVisibility.PUBLIC)
        coEvery { profileService.getById(profileId) } returns profile
        coEvery { profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW) } returns false
        return profile
    }

    @Test
    fun `marks returns marks for all step metadata`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val stepMeta1 = UUID.random()
        val stepMeta2 = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(1L)
        )

        mockProfileAllowed(profileId)
        coEvery { guideService.getGuideSteps(metadataId, 1) } returns listOf(
            GuideStep(id = 1L, metadataId = metadataId, version = 1, stepMetadataId = stepMeta1, stepMetadataVersion = 1, sort = 1),
            GuideStep(id = 2L, metadataId = metadataId, version = 1, stepMetadataId = stepMeta2, stepMetadataVersion = 1, sort = 2)
        )
        val expectedMarks = listOf(
            ProfileMark(id = 1, profileId = profileId, metadataId = stepMeta1, metadataVersion = 1),
            ProfileMark(id = 2, profileId = profileId, metadataId = stepMeta2, metadataVersion = 1)
        )
        coEvery { markService.getMarks(profileId, listOf(stepMeta1, stepMeta2)) } returns expectedMarks

        val result = controller.marks(authentication, progress)

        assertEquals(2, result.size)
        assertEquals(expectedMarks, result)
        coVerify { markService.getMarks(profileId, listOf(stepMeta1, stepMeta2)) }
    }

    @Test
    fun `marks returns empty list when permission denied`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(1L)
        )

        mockProfileDenied(profileId)

        val result = controller.marks(authentication, progress)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `marks returns empty list when steps have no metadata`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap())
        )

        mockProfileAllowed(profileId)
        coEvery { guideService.getGuideSteps(metadataId, 1) } returns listOf(
            GuideStep(id = 1L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 1)
        )

        val result = controller.marks(authentication, progress)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `marks returns empty list when guide has no steps`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = profileId,
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap())
        )

        mockProfileAllowed(profileId)
        coEvery { guideService.getGuideSteps(metadataId, 1) } returns emptyList()

        val result = controller.marks(authentication, progress)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `nextStepId returns first incomplete step when no steps completed`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = emptyList()
        )

        coEvery { guideService.getGuideSteps(metadataId, 1) } returns listOf(
            GuideStep(id = 10L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 1),
            GuideStep(id = 20L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 2)
        )

        val result = controller.nextStepId(progress)

        assertEquals(10L, result)
    }

    @Test
    fun `nextStepId returns step after last completed`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(10L)
        )

        coEvery { guideService.getGuideSteps(metadataId, 1) } returns listOf(
            GuideStep(id = 10L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 1),
            GuideStep(id = 20L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 2),
            GuideStep(id = 30L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 3)
        )

        val result = controller.nextStepId(progress)

        assertEquals(20L, result)
    }

    @Test
    fun `nextStepId returns null when all steps are completed`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = listOf(10L, 20L)
        )

        coEvery { guideService.getGuideSteps(metadataId, 1) } returns listOf(
            GuideStep(id = 10L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 1),
            GuideStep(id = 20L, metadataId = metadataId, version = 1, stepMetadataId = null, stepMetadataVersion = null, sort = 2)
        )

        val result = controller.nextStepId(progress)

        assertNull(result)
    }

    @Test
    fun `nextStepId returns null when no guide steps exist`() = runTest {
        val metadataId = UUID.random()
        val progress = ProfileGuideProgress(
            profileId = UUID.random(),
            metadataId = metadataId,
            version = 1,
            attributes = JsonObject(emptyMap()),
            completedStepIds = emptyList()
        )

        coEvery { guideService.getGuideSteps(metadataId, 1) } returns emptyList()

        val result = controller.nextStepId(progress)

        assertNull(result)
    }
}

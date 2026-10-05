package bosca.experimentation.service

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.segmentation.model.Segment
import bosca.segmentation.model.SegmentStatus
import bosca.segmentation.model.SegmentType
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluationContextTest {

    private fun profile(id: UUID = UUID.random()) = Profile(
        id = id,
        type = ProfileType.GENERIC,
        name = "Profile",
        visibility = ProfileVisibility.USER,
    )

    private fun attribute(profileId: UUID, typeId: String) = ProfileAttribute(
        profile = profileId,
        typeId = typeId,
        visibility = ProfileVisibility.USER,
        confidence = 100,
        priority = 0,
        source = "test",
        attributes = buildJsonObject { put("value", typeId) },
    )

    private fun segment(id: UUID = UUID.random()) = Segment(
        id = id,
        name = "Segment",
        type = SegmentType.STATIC,
        status = SegmentStatus.ACTIVE,
    )

    @Test
    fun `identifier remains installation based after login`() {
        val principalId = UUID.random()

        assertEquals(
            "installation",
            EvaluationContext(principalId, "installation", null, null).identifier,
        )
        assertEquals(
            "installation",
            EvaluationContext(null, "installation", null, null).identifier,
        )
    }

    @Test
    fun `markDegraded records external preload failure`() {
        val context = EvaluationContext(null, "installation", null, null)

        assertFalse(context.degraded)
        context.markDegraded()
        assertTrue(context.degraded)
    }

    @Test
    fun `preloaded profile attributes are returned without service access`() = runTest {
        val profileService = mockk<ProfileService>()
        val expected = listOf(attribute(UUID.random(), "preloaded"))
        val context = EvaluationContext(
            UUID.random(),
            "installation",
            null,
            profileService,
            profileAttributes = expected,
        )

        assertEquals(expected, context.profileAttributes())
        coVerify(exactly = 0) { profileService.getByPrincipal(any()) }
    }

    @Test
    fun `profile attributes are empty without both principal and profile service`() = runTest {
        val profileService = mockk<ProfileService>()

        assertEquals(
            emptyList(),
            EvaluationContext(null, "installation", null, profileService).profileAttributes(),
        )
        assertEquals(
            emptyList(),
            EvaluationContext(UUID.random(), "installation", null, null).profileAttributes(),
        )
        coVerify(exactly = 0) { profileService.getByPrincipal(any()) }
    }

    @Test
    fun `profile attributes load all profiles once`() = runTest {
        val principalId = UUID.random()
        val firstProfile = profile()
        val secondProfile = profile()
        val firstAttribute = attribute(firstProfile.id, "first")
        val secondAttribute = attribute(secondProfile.id, "second")
        val profileService = mockk<ProfileService>()
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(firstProfile, secondProfile)
        coEvery { profileService.getAttributes(firstProfile.id) } returns listOf(firstAttribute)
        coEvery { profileService.getAttributes(secondProfile.id) } returns listOf(secondAttribute)
        val context = EvaluationContext(principalId, "installation", null, profileService)

        assertEquals(listOf(firstAttribute, secondAttribute), context.profileAttributes())
        assertEquals(listOf(firstAttribute, secondAttribute), context.profileAttributes())

        coVerify(exactly = 1) { profileService.getByPrincipal(principalId) }
        coVerify(exactly = 1) { profileService.getAttributes(firstProfile.id) }
        coVerify(exactly = 1) { profileService.getAttributes(secondProfile.id) }
    }

    @Test
    fun `profile lookup failure degrades and caches an empty result`() = runTest {
        val principalId = UUID.random()
        val profileService = mockk<ProfileService>()
        coEvery { profileService.getByPrincipal(principalId) } throws IllegalStateException("unavailable")
        val context = EvaluationContext(principalId, "installation", null, profileService)

        assertEquals(emptyList(), context.profileAttributes())
        assertEquals(emptyList(), context.profileAttributes())
        assertTrue(context.degraded)
        coVerify(exactly = 1) { profileService.getByPrincipal(principalId) }
    }

    @Test
    fun `attribute lookup failure keeps other attributes and degrades`() = runTest {
        val principalId = UUID.random()
        val failedProfile = profile()
        val healthyProfile = profile()
        val healthyAttribute = attribute(healthyProfile.id, "healthy")
        val profileService = mockk<ProfileService>()
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(failedProfile, healthyProfile)
        coEvery { profileService.getAttributes(failedProfile.id) } throws IllegalStateException("unavailable")
        coEvery { profileService.getAttributes(healthyProfile.id) } returns listOf(healthyAttribute)
        val context = EvaluationContext(principalId, "installation", null, profileService)

        assertEquals(listOf(healthyAttribute), context.profileAttributes())
        assertTrue(context.degraded)
    }

    @Test
    fun `profile and attribute cancellation are rethrown`() = runTest {
        val principalId = UUID.random()
        val profile = profile()
        val profileService = mockk<ProfileService>()
        coEvery { profileService.getByPrincipal(principalId) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            EvaluationContext(principalId, "installation", null, profileService).profileAttributes()
        }

        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile)
        coEvery { profileService.getAttributes(profile.id) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            EvaluationContext(principalId, "installation", null, profileService).profileAttributes()
        }
    }

    @Test
    fun `preloaded segments are returned without service access`() = runTest {
        val profileService = mockk<ProfileService>()
        val segmentService = mockk<SegmentService>()
        val expected = setOf(UUID.random())
        val context = EvaluationContext(
            UUID.random(),
            "installation",
            null,
            profileService,
            memberSegmentIds = expected,
        )

        assertEquals(expected, context.memberSegmentIds(segmentService))
        coVerify(exactly = 0) { profileService.getByPrincipal(any()) }
        coVerify(exactly = 0) { segmentService.getSegmentsByProfileId(any()) }
    }

    @Test
    fun `segments are empty without both principal and profile service`() = runTest {
        val profileService = mockk<ProfileService>()
        val segmentService = mockk<SegmentService>()

        assertEquals(
            emptySet(),
            EvaluationContext(null, "installation", null, profileService).memberSegmentIds(segmentService),
        )
        assertEquals(
            emptySet(),
            EvaluationContext(UUID.random(), "installation", null, null).memberSegmentIds(segmentService),
        )
        coVerify(exactly = 0) { profileService.getByPrincipal(any()) }
        coVerify(exactly = 0) { segmentService.getSegmentsByProfileId(any()) }
    }

    @Test
    fun `segments load every profile and cache the deduplicated union`() = runTest {
        val principalId = UUID.random()
        val firstProfile = profile()
        val secondProfile = profile()
        val sharedSegment = segment()
        val secondSegment = segment()
        val profileService = mockk<ProfileService>()
        val segmentService = mockk<SegmentService>()
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(firstProfile, secondProfile)
        coEvery { segmentService.getSegmentsByProfileId(firstProfile.id) } returns listOf(sharedSegment)
        coEvery {
            segmentService.getSegmentsByProfileId(secondProfile.id)
        } returns listOf(sharedSegment, secondSegment)
        val context = EvaluationContext(principalId, "installation", null, profileService)

        val expected = setOf(sharedSegment.id, secondSegment.id)
        assertEquals(expected, context.memberSegmentIds(segmentService))
        assertEquals(expected, context.memberSegmentIds(segmentService))

        coVerify(exactly = 1) { profileService.getByPrincipal(principalId) }
        coVerify(exactly = 1) { segmentService.getSegmentsByProfileId(firstProfile.id) }
        coVerify(exactly = 1) { segmentService.getSegmentsByProfileId(secondProfile.id) }
    }

    @Test
    fun `segment lookup failure degrades and caches empty set`() = runTest {
        val principalId = UUID.random()
        val profileService = mockk<ProfileService>()
        val segmentService = mockk<SegmentService>()
        coEvery { profileService.getByPrincipal(principalId) } throws IllegalStateException("unavailable")
        val context = EvaluationContext(principalId, "installation", null, profileService)

        assertEquals(emptySet(), context.memberSegmentIds(segmentService))
        assertEquals(emptySet(), context.memberSegmentIds(segmentService))
        assertTrue(context.degraded)
        coVerify(exactly = 1) { profileService.getByPrincipal(principalId) }
    }

    @Test
    fun `segment cancellation is rethrown`() = runTest {
        val principalId = UUID.random()
        val profileService = mockk<ProfileService>()
        val segmentService = mockk<SegmentService>()
        coEvery { profileService.getByPrincipal(principalId) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            EvaluationContext(principalId, "installation", null, profileService)
                .memberSegmentIds(segmentService)
        }
    }
}

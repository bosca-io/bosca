package bosca.profile.profile.graphql

import bosca.profile.attribute.service.ProfileAttributeService
import bosca.profile.attribute.verification.AttributeVerificationService
import bosca.profile.bookmark.service.ProfileBookmarkService
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.mark.service.ProfileMarkService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.rating.service.ProfileRatingService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileMutationControllerTest {

    private val bookmarkService = mockk<ProfileBookmarkService>()
    private val markService = mockk<ProfileMarkService>()
    private val guideService = mockk<ProfileGuideService>()
    private val ratingService = mockk<ProfileRatingService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val attributeService = mockk<ProfileAttributeService>()
    private val attributeVerificationService = mockk<AttributeVerificationService>(relaxed = true)
    private val profileService = mockk<ProfileService>()

    private val controller = ProfileMutationController(
        bookmarkService,
        markService,
        guideService,
        ratingService,
        permissionEvaluator,
        attributeService,
        attributeVerificationService,
        profileService
    )

    /** A ServerCall whose app origin the controller reads to route the verification email back. */
    private fun originCall(): ServerCall = mockk { every { request.appOrigin } returns "https://studio.test" }

    private fun createProfile(principalId: UUID): Profile {
        return Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Test",
            visibility = ProfileVisibility.PUBLIC
        )
    }

    private fun createAuthContext(principalId: UUID): AuthenticationContext {
        val authentication = mockk<AuthenticationContext>()
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        return authentication
    }

    private fun stubPrimaryProfile(principalId: UUID, profile: Profile) {
        coEvery { profileService.getPrimaryProfile(match { it.id == principalId }) } returns profile
    }

    @Test
    fun `thirdparty returns ThirdPartyExtensionMutation`() {
        val result = controller.thirdparty()

        assertNotNull(result)
    }

    @Test
    fun `addBookmark with metadata calls bookmark service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val metadataId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { bookmarkService.addBookmark(profile.id, metadataId, 1, null, null) } just Runs

        val result = controller.addBookmark(authentication, metadataId = metadataId, version = 1)

        assertTrue(result)
    }

    @Test
    fun `addBookmark with collection calls bookmark service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val collectionId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { bookmarkService.addBookmark(profile.id, null, null, collectionId, null) } just Runs

        val result = controller.addBookmark(authentication, collectionId = collectionId)

        assertTrue(result)
    }

    @Test
    fun `deleteBookmark with metadata calls bookmark service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val metadataId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { bookmarkService.deleteBookmark(profile.id, metadataId, 1, null) } just Runs

        val result = controller.deleteBookmark(authentication, metadataId = metadataId, version = 1)

        assertTrue(result)
    }

    @Test
    fun `addMark with metadata calls mark service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val metadataId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { markService.addMark(profile.id, metadataId, null, null, null) } just Runs

        val result = controller.addMark(authentication, metadataId = metadataId)

        assertTrue(result)
    }

    @Test
    fun `deleteMark calls mark service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { markService.deleteMark(42L, profile.id) } just Runs

        val result = controller.deleteMark(authentication, 42L)

        assertTrue(result)
        coVerify { markService.deleteMark(42L, profile.id) }
    }

    @Test
    fun `deleteProgress calls guide service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val metadataId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { guideService.deleteProgress(profile.id, metadataId, 1) } just Runs

        val result = controller.deleteProgress(authentication, metadataId = metadataId, metadataVersion = 1)

        assertTrue(result)
        coVerify { guideService.deleteProgress(profile.id, metadataId, 1) }
    }

    @Test
    fun `deleteProgress with specific profileId uses that profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Specific",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = createAuthContext(principalId)
        val metadataId = UUID.random()

        coEvery { profileService.getById(profileId) } returns profile
        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT) } returns true
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { guideService.deleteProgress(profileId, metadataId, 2) } just Runs

        val result = controller.deleteProgress(authentication, metadataId = metadataId, metadataVersion = 2, profileId = profileId)

        assertTrue(result)
        coVerify { guideService.deleteProgress(profileId, metadataId, 2) }
    }

    @Test
    fun `addRating with metadata calls rating service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val metadataId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { ratingService.addRating(profileId = profile.id, rating = 5, metadataId = metadataId, metadataVersion = null, collectionId = null) } returns mockk()

        val result = controller.addRating(authentication, 5, metadataId = metadataId)

        assertTrue(result)
    }

    @Test
    fun `deleteAttribute calls attribute service`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)
        val attributeId = UUID.random()

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { attributeService.deleteAttribute(profile.id, attributeId) } returns profile.id

        val result = controller.deleteAttribute(authentication, attributeId)

        assertTrue(result)
        coVerify { attributeService.deleteAttribute(profile.id, attributeId) }
    }

    @Test
    fun `requestAttributeVerification drives the framework for the caller's own profile`() = runTest {
        val principalId = UUID.random()
        val profile = createProfile(principalId)
        val authentication = createAuthContext(principalId)

        stubPrimaryProfile(principalId, profile)
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs

        val result = controller.requestAttributeVerification(authentication, "bosca.profiles.email", call = originCall())

        assertTrue(result)
        coVerify { attributeVerificationService.requestVerification(profile.id, "bosca.profiles.email", any()) }
    }

    @Test
    fun `requestAttributeVerification enforces edit permission on the targeted profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Specific",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = createAuthContext(principalId)

        coEvery { profileService.getById(profileId) } returns profile
        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT) } returns true
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs

        val result = controller.requestAttributeVerification(authentication, "bosca.profiles.email", profileId, originCall())

        assertTrue(result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) }
        coVerify { attributeVerificationService.requestVerification(profileId, "bosca.profiles.email", any()) }
    }

    @Test
    fun `addBookmark with specific profileId uses that profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Specific",
            visibility = ProfileVisibility.PUBLIC
        )
        val authentication = createAuthContext(principalId)
        val collectionId = UUID.random()

        coEvery { profileService.getById(profileId) } returns profile
        coEvery { permissionEvaluator.isAllowed(authentication, profile, PermissionAction.EDIT) } returns true
        coEvery { permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.EDIT) } just Runs
        coEvery { bookmarkService.addBookmark(profileId, null, null, collectionId, null) } just Runs

        val result = controller.addBookmark(authentication, collectionId = collectionId, profileId = profileId)

        assertTrue(result)
    }
}

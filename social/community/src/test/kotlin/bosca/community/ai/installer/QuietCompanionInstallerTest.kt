@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.community.ai.installer

import bosca.community.ai.configuration.CommunityAIConfiguration
import bosca.di.ProviderRegistry
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.security.model.SimplePasswordAttributes
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QuietCompanionInstallerTest {

    private val securityService = mockk<SecurityService>()
    private val profileService = mockk<ProfileService>()

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `new Buddy principal is entitled to messaging and receives a profile`() = runTest {
        val principalId = UUID.random()
        val messagingGroup = Group(UUID.random(), "messaging", "Messaging", GroupType.SYSTEM)
        val credentials = slot<SimplePasswordAttributes>()
        coEvery { securityService.getGroupByName("messaging", GroupType.SYSTEM) } returns messagingGroup
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns null
        coEvery { securityService.addPrincipal(any(), capture(credentials)) } returns AuthenticatedPrincipal(
            Principal(id = principalId),
            emptyList(),
        )
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery { securityService.addPrincipalGroup(principalId, messagingGroup.id) } returns Unit
        coEvery { profileService.getByPrincipal(principalId) } returns emptyList()
        coEvery {
            profileService.add(any(), ProfileType.GENERIC, principalId)
        } returns mockk<Profile>()
        val (installation, version) = communityAiPackage()

        QuietCompanionInstaller(securityService, profileService, application())
            .install(installation, version)

        assertEquals("Buddy", credentials.captured.identifier)
        assertEquals("buddy-password", credentials.captured.password)
        coVerify(exactly = 1) { securityService.addPrincipalGroup(principalId, messagingGroup.id) }
        coVerify(exactly = 1) {
            profileService.add(
                match<ProfileInput> { it.name == "Buddy" },
                ProfileType.GENERIC,
                principalId,
            )
        }
    }

    @Test
    fun `existing Buddy installation is repaired idempotently`() = runTest {
        val principalId = UUID.random()
        val messagingGroup = Group(UUID.random(), "messaging", "Messaging", GroupType.SYSTEM)
        coEvery { securityService.getGroupByName("messaging", GroupType.SYSTEM) } returns messagingGroup
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns Principal(id = principalId)
        coEvery { securityService.getPrincipalGroups(principalId) } returnsMany listOf(
            emptyList(),
            listOf(messagingGroup),
        )
        coEvery { securityService.addPrincipalGroup(principalId, messagingGroup.id) } returns Unit
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(profile(principalId))
        val (installation, version) = communityAiPackage()

        val installer = QuietCompanionInstaller(securityService, profileService, application())
        installer.install(installation, version)
        installer.install(installation, version)

        assertEquals("1.1.0", installer.version)
        assertEquals("1.1.0", version.version)
        coVerify(exactly = 0) { securityService.addPrincipal(any(), any()) }
        coVerify(exactly = 1) { securityService.addPrincipalGroup(principalId, messagingGroup.id) }
        coVerify(exactly = 0) { profileService.add(any(), any(), any()) }
    }

    @Test
    fun `deleted Buddy principal and profile are restored`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val messagingGroup = Group(UUID.random(), "messaging", "Messaging", GroupType.SYSTEM)
        val deletedPrincipal = Principal(id = principalId, deletedAt = OffsetDateTime.now())
        val deletedProfile = profile(principalId, profileId, OffsetDateTime.now())
        coEvery { securityService.getGroupByName("messaging", GroupType.SYSTEM) } returns messagingGroup
        coEvery { securityService.getPrincipalByIdentifier("Buddy") } returns deletedPrincipal
        coEvery { securityService.restorePrincipal(principalId) } returns deletedPrincipal.copy(deletedAt = null)
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(messagingGroup)
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(deletedProfile)
        coEvery { profileService.restore(profileId) } returns deletedProfile.copy(deletedAt = null)
        val (installation, version) = communityAiPackage()

        QuietCompanionInstaller(securityService, profileService, application())
            .install(installation, version)

        coVerify(exactly = 1) { securityService.restorePrincipal(principalId) }
        coVerify(exactly = 1) { profileService.restore(profileId) }
        coVerify(exactly = 0) { securityService.addPrincipal(any(), any()) }
        coVerify(exactly = 0) { profileService.add(any(), any(), any()) }
    }

    private fun communityAiPackage() = CommunityAIConfiguration().communityAiPackage().let {
        it to it.versions.single()
    }

    private fun profile(
        principalId: UUID,
        profileId: UUID = UUID.random(),
        deletedAt: OffsetDateTime? = null,
    ) = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = principalId,
        name = "Buddy",
        visibility = bosca.profile.model.ProfileVisibility.USER,
        deletedAt = deletedAt,
    )

    private fun application(): BoscaApplication = BoscaApplication(
        ApplicationConfig.load(
            """
            initialization:
              password:
                Buddy: buddy-password
            """.trimIndent().byteInputStream(),
        )
    )
}

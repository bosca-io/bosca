package bosca.hubspot.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HubSpotAttributeTypesInstallerTest {

    private val service = mockk<ProfileService>()
    private val installer = HubSpotAttributeTypesInstaller(service)
    private val installation = PackageInstallation(key = "hubspot", name = "HubSpot", versions = emptyList())
    private val version = PackageInstallationVersion(version = "1.0.0", installerNames = listOf("hubspot-attribute-types"))

    @Test
    fun `install adds the hubspot id type when it does not exist`() = runTest {
        val input = slot<ProfileAttributeTypeInput>()
        coEvery { service.getAttributeTypes() } returns emptyList()
        coEvery { service.addAttributeType(capture(input)) } answers { input.captured.toType() }

        installer.install(installation, version)

        coVerify(exactly = 1) { service.addAttributeType(any()) }
        coVerify(exactly = 0) { service.editAttributeType(any()) }
        assertEquals(HubSpotAttributeTypesInstaller.HUBSPOT_ID_TYPE, input.captured.id)
        assertEquals(ProfileVisibility.SYSTEM, input.captured.visibility)
        assertFalse(input.captured.protected, "type must not be protected or addAttributes silently drops the sync jobs' writes")
    }

    @Test
    fun `install edits the hubspot id type when it already exists`() = runTest {
        val input = slot<ProfileAttributeTypeInput>()
        coEvery { service.getAttributeTypes() } returns listOf(
            ProfileAttributeType(
                id = HubSpotAttributeTypesInstaller.HUBSPOT_ID_TYPE,
                description = "manually created",
                name = "HubSpot ID",
                visibility = ProfileVisibility.SYSTEM,
                protected = false
            )
        )
        coEvery { service.editAttributeType(capture(input)) } answers { input.captured.toType() }

        installer.install(installation, version)

        coVerify(exactly = 0) { service.addAttributeType(any()) }
        coVerify(exactly = 1) { service.editAttributeType(any()) }
        assertEquals(HubSpotAttributeTypesInstaller.HUBSPOT_ID_TYPE, input.captured.id)
    }

    private fun ProfileAttributeTypeInput.toType() = ProfileAttributeType(
        id = id,
        description = description,
        name = name,
        visibility = visibility,
        protected = protected,
        formSchemaId = formSchemaId
    )
}

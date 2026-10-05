package bosca.recommendations.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class TuningAttributeTypeInstallerTest {

    private val installation = PackageInstallation(key = "recommendations", name = "Recommendations", versions = emptyList())
    private val version = PackageInstallationVersion(version = "1.0.4", installerNames = listOf("recommendations-tuning-attribute-type"))

    private fun tuningType(): ProfileAttributeType = ProfileAttributeType(
        id = TuningAttributeTypeInstaller.TUNING_TYPE,
        name = "Feed Tuning",
        description = "old description",
        visibility = ProfileVisibility.USER,
        protected = false,
    )

    @Test
    fun `adds the tuning attribute type when missing`() = runTest {
        val service = mockk<ProfileService>()
        val added = mutableListOf<ProfileAttributeTypeInput>()
        coEvery { service.getAttributeTypes() } returns emptyList()
        coEvery { service.addAttributeType(capture(added)) } answers { tuningType() }

        TuningAttributeTypeInstaller(service).install(installation, version)

        val input = added.single()
        assertEquals("bosca.recommendations.tuning", input.id)
        assertEquals(ProfileVisibility.USER, input.visibility)
        // addAttributes silently skips protected types, so the type must stay writable.
        assertFalse(input.protected)
        coVerify(exactly = 0) { service.editAttributeType(any()) }
    }

    @Test
    fun `edits the tuning attribute type when it already exists`() = runTest {
        val service = mockk<ProfileService>()
        val edited = mutableListOf<ProfileAttributeTypeInput>()
        coEvery { service.getAttributeTypes() } returns listOf(tuningType())
        coEvery { service.editAttributeType(capture(edited)) } answers { tuningType() }

        TuningAttributeTypeInstaller(service).install(installation, version)

        assertEquals("bosca.recommendations.tuning", edited.single().id)
        coVerify(exactly = 0) { service.addAttributeType(any()) }
    }
}

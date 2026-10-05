package bosca.recommendations.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.attribute.model.ProfileAttributeTypeInput
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.recommendations.pipeline.InferInterestNode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class LearnedInterestAttributeTypeInstallerTest {

    private val installation = PackageInstallation(key = "recommendations", name = "Recommendations", versions = emptyList())
    private val version = PackageInstallationVersion(version = "1.0.8", installerNames = listOf("recommendations-learned-interest-attribute-type"))

    private fun learnedType(): ProfileAttributeType = ProfileAttributeType(
        id = InferInterestNode.LEARNED_INTEREST_TYPE,
        name = "Learned Interest",
        description = "old description",
        visibility = ProfileVisibility.USER,
        protected = false,
    )

    @Test
    fun `adds the learned-interest attribute type when missing`() = runTest {
        val service = mockk<ProfileService>()
        val added = mutableListOf<ProfileAttributeTypeInput>()
        coEvery { service.getAttributeTypes() } returns emptyList()
        coEvery { service.addAttributeType(capture(added)) } answers { learnedType() }

        LearnedInterestAttributeTypeInstaller(service).install(installation, version)

        val input = added.single()
        assertEquals("bosca.recommendations.learned_interest", input.id)
        assertEquals(ProfileVisibility.USER, input.visibility)
        // addAttributes silently skips protected types, so the learned type must stay writable.
        assertFalse(input.protected)
        coVerify(exactly = 0) { service.editAttributeType(any()) }
    }

    @Test
    fun `edits the learned-interest attribute type when it already exists`() = runTest {
        val service = mockk<ProfileService>()
        val edited = mutableListOf<ProfileAttributeTypeInput>()
        coEvery { service.getAttributeTypes() } returns listOf(learnedType())
        coEvery { service.editAttributeType(capture(edited)) } answers { learnedType() }

        LearnedInterestAttributeTypeInstaller(service).install(installation, version)

        assertEquals("bosca.recommendations.learned_interest", edited.single().id)
        coVerify(exactly = 0) { service.addAttributeType(any()) }
    }
}

package bosca.profile.installer

import bosca.forms.model.FormSchema
import bosca.forms.model.FormSchemaInput
import bosca.forms.service.FormSchemaService
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileAttributeFormSchemaInstallerTest {

    @Test
    fun `generated schemas use canonical attribute properties and are publicly viewable`() = runTest {
        val profileService = mockk<ProfileService>()
        val formSchemaService = mockk<FormSchemaService>()
        val schemaId = UUID.random()
        val saved = mockk<FormSchema> {
            every { id } returns schemaId
            every { published } returns false
        }
        val emailType = ProfileAttributeType(
            id = "bosca.profiles.email",
            name = "Email",
            description = "Email address",
            visibility = ProfileVisibility.USER,
            protected = false,
        )
        val input = slot<FormSchemaInput>()
        coEvery { profileService.getAttributeTypes() } returns listOf(emailType)
        coEvery { formSchemaService.save(capture(input)) } returns saved
        coEvery { formSchemaService.setPublished(schemaId, true) } returns saved
        coEvery { profileService.editAttributeType(any()) } returns emailType.copy(formSchemaId = schemaId)

        val installer = ProfileAttributeFormSchemaInstaller(profileService, formSchemaService)
        installer.install(mockk<PackageInstallation>(), mockk<PackageInstallationVersion>())

        assertEquals("1.1.0", installer.version)
        assertTrue(input.captured.public)
        assertEquals(
            setOf("email"),
            input.captured.schema.jsonObject.getValue("properties").jsonObject.keys,
        )
        assertEquals(
            "email",
            input.captured.uiSchema.jsonObject.getValue("layout").jsonArray
                .single().jsonObject.getValue("property").jsonPrimitive.content,
        )
        coVerify { formSchemaService.setPublished(schemaId, true) }
    }

    @Test
    fun `installer preserves a custom linked attribute form`() = runTest {
        val profileService = mockk<ProfileService>()
        val formSchemaService = mockk<FormSchemaService>()
        val customSchemaId = UUID.random()
        val type = ProfileAttributeType(
            id = "custom.attribute",
            name = "Custom",
            description = "Custom attribute",
            visibility = ProfileVisibility.USER,
            protected = false,
            formSchemaId = customSchemaId,
        )
        val customSchema = mockk<FormSchema> {
            every { key } returns "custom.profile.attribute.form"
        }
        coEvery { profileService.getAttributeTypes() } returns listOf(type)
        coEvery { formSchemaService.getById(customSchemaId) } returns customSchema

        ProfileAttributeFormSchemaInstaller(profileService, formSchemaService).install(
            mockk<PackageInstallation>(),
            mockk<PackageInstallationVersion>(),
        )

        coVerifyAll {
            profileService.getAttributeTypes()
            formSchemaService.getById(customSchemaId)
        }
        coVerify(exactly = 0) { formSchemaService.save(any()) }
        coVerify(exactly = 0) { profileService.editAttributeType(any()) }
    }
}

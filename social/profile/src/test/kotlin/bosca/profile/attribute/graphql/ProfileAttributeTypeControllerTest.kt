package bosca.profile.attribute.graphql

import bosca.forms.model.FormSchema
import bosca.forms.security.FormSchemaPermissionEvaluator
import bosca.forms.service.FormSchemaService
import bosca.profile.attribute.model.ProfileAttributeType
import bosca.profile.model.ProfileVisibility
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileAttributeTypeControllerTest {

    private val formSchemaService = mockk<FormSchemaService>()
    private val formSchemaPermissionEvaluator = mockk<FormSchemaPermissionEvaluator>()
    private val controller = ProfileAttributeTypeController(formSchemaService, formSchemaPermissionEvaluator)

    @Test
    fun `id returns type id`() {
        val type = ProfileAttributeType(
            id = "bosca.profiles.email",
            name = "Email",
            description = "Email address",
            visibility = ProfileVisibility.USER,
            protected = false
        )

        assertEquals("bosca.profiles.email", controller.id(type))
    }

    @Test
    fun `name returns type name`() {
        val type = ProfileAttributeType(
            id = "bosca.profiles.name",
            name = "Name",
            description = "Profile name",
            visibility = ProfileVisibility.USER,
            protected = false
        )

        assertEquals("Name", controller.name(type))
    }

    @Test
    fun `description returns type description`() {
        val type = ProfileAttributeType(
            id = "bosca.profiles.bio",
            name = "Bio",
            description = "A profile biography",
            visibility = ProfileVisibility.USER,
            protected = false
        )

        assertEquals("A profile biography", controller.description(type))
    }

    @Test
    fun `visibility returns type visibility`() {
        val type = ProfileAttributeType(
            id = "bosca.profiles.email",
            name = "Email",
            description = "Email",
            visibility = ProfileVisibility.PUBLIC,
            protected = false
        )

        assertEquals(ProfileVisibility.PUBLIC, controller.visibility(type))
    }

    @Test
    fun `isProtected returns false for non-protected type`() {
        val type = ProfileAttributeType(
            id = "bosca.profiles.email",
            name = "Email",
            description = "Email",
            visibility = ProfileVisibility.USER,
            protected = false
        )

        assertFalse(controller.isProtected(type))
    }

    @Test
    fun `isProtected returns true for protected type`() {
        val type = ProfileAttributeType(
            id = "bosca.profiles.comment.disabled",
            name = "Commenting Disabled",
            description = "Commenting disabled",
            visibility = ProfileVisibility.SYSTEM,
            protected = true
        )

        assertTrue(controller.isProtected(type))
    }

    @Test
    fun `formSchema verifies view permission before returning linked schema`() = runTest {
        val formSchemaId = UUID.random()
        val authentication = mockk<AuthenticationContext>()
        val formSchema = mockk<FormSchema>()
        val type = ProfileAttributeType(
            id = "bosca.profiles.email",
            name = "Email",
            description = "Email",
            visibility = ProfileVisibility.USER,
            protected = false,
            formSchemaId = formSchemaId,
        )
        coEvery { formSchemaService.getById(formSchemaId) } returns formSchema
        coEvery {
            formSchemaPermissionEvaluator.verifyAllowed(authentication, formSchema, PermissionAction.VIEW)
        } just Runs

        assertEquals(formSchema, controller.formSchema(authentication, type))
        coVerify {
            formSchemaPermissionEvaluator.verifyAllowed(authentication, formSchema, PermissionAction.VIEW)
        }
    }
}

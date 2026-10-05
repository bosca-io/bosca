package bosca.forms.graphql

import bosca.forms.model.FormSchemaProfileMapping
import bosca.forms.model.FormSchemaProfileMappingAttribute
import bosca.profile.model.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals

class FormSchemaProfileMappingTypeControllerTest {

    private val controller = FormSchemaProfileMappingTypeController()

    private val mapping = FormSchemaProfileMapping(
        nameField = "full_name",
        visibility = ProfileVisibility.USER,
        attributes = listOf(
            FormSchemaProfileMappingAttribute("bosca.profiles.email", "email", "email"),
            FormSchemaProfileMappingAttribute("bosca.profiles.name", "full_name", "name")
        )
    )

    @Test
    fun `nameField returns the mapped name field`() {
        assertEquals("full_name", controller.nameField(mapping))
    }

    @Test
    fun `visibility returns the profile visibility`() {
        assertEquals(ProfileVisibility.USER, controller.visibility(mapping))
    }

    @Test
    fun `attributes returns the attribute mappings`() {
        assertEquals(2, controller.attributes(mapping).size)
        assertEquals("bosca.profiles.email", controller.attributes(mapping)[0].typeId)
    }
}

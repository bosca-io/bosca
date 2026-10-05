package bosca.forms.graphql

import bosca.forms.model.FormSchemaProfileMappingAttribute
import kotlin.test.Test
import kotlin.test.assertEquals

class FormSchemaProfileMappingAttributeTypeControllerTest {

    private val controller = FormSchemaProfileMappingAttributeTypeController()

    private val attr = FormSchemaProfileMappingAttribute(
        typeId = "bosca.profiles.email",
        field = "contact_email",
        attributeKey = "email"
    )

    @Test
    fun `typeId returns the attribute type identifier`() {
        assertEquals("bosca.profiles.email", controller.typeId(attr))
    }

    @Test
    fun `field returns the form field name`() {
        assertEquals("contact_email", controller.field(attr))
    }

    @Test
    fun `attributeKey returns the attribute JSON key`() {
        assertEquals("email", controller.attributeKey(attr))
    }
}

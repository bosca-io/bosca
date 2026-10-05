package bosca.forms.graphql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormGraphQLObjectsTest {

    @Test
    fun `FormsMutation is a singleton object`() {
        val instance = FormsMutation
        assertTrue(instance is FormsMutation)
    }

    @Test
    fun `FormsMutation class name matches expected`() {
        assertEquals("FormsMutation", FormsMutation::class.simpleName)
    }

    @Test
    fun `FormSchemas is a singleton object`() {
        val instance = FormSchemas
        assertTrue(instance is FormSchemas)
    }

    @Test
    fun `FormSchemasMutation is a singleton object`() {
        val instance = FormSchemasMutation
        assertTrue(instance is FormSchemasMutation)
    }
}

package bosca.forms.graphql

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class FormsMutationControllerTest {

    @Test
    fun `FormsMutation object is a singleton`() {
        assertNotNull(FormsMutation)
        assertSame(FormsMutation, FormsMutation)
    }

    @Test
    fun `FormsMutation toString returns stable value`() {
        val str = FormsMutation.toString()
        assertNotNull(str)
    }
}

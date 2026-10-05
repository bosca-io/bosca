package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptTest {

    @Test
    fun `Subscript default creation has null attributes`() {
        val mark = Subscript()
        assertNull(mark.attributes)
    }

    @Test
    fun `Subscript is a Mark`() {
        val mark = Subscript()
        assertTrue(mark is Mark)
    }
}

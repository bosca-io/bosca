package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuperscriptTest {

    @Test
    fun `Superscript default creation has null attributes`() {
        val mark = Superscript()
        assertNull(mark.attributes)
    }

    @Test
    fun `Superscript is a Mark`() {
        val mark = Superscript()
        assertTrue(mark is Mark)
    }
}

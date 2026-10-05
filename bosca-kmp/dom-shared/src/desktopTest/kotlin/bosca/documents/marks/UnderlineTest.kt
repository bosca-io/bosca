package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnderlineTest {

    @Test
    fun `Underline default creation has null attributes`() {
        val mark = Underline()
        assertNull(mark.attributes)
    }

    @Test
    fun `Underline is a Mark`() {
        val mark = Underline()
        assertTrue(mark is Mark)
    }
}

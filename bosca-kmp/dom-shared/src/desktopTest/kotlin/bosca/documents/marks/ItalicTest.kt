package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItalicTest {

    @Test
    fun `Italic default creation has null attributes`() {
        val mark = Italic()
        assertNull(mark.attributes)
    }

    @Test
    fun `Italic is a Mark`() {
        val mark = Italic()
        assertTrue(mark is Mark)
    }
}

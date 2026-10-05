package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoldTest {

    @Test
    fun `Bold default creation has null attributes`() {
        val mark = Bold()
        assertNull(mark.attributes)
    }

    @Test
    fun `Bold is a Mark`() {
        val mark = Bold()
        assertTrue(mark is Mark)
    }
}

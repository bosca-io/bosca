package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HiddenTest {

    @Test
    fun `Hidden default creation has null attributes`() {
        val mark = Hidden()
        assertNull(mark.attributes)
    }

    @Test
    fun `Hidden is a Mark`() {
        val mark = Hidden()
        assertTrue(mark is Mark)
    }
}

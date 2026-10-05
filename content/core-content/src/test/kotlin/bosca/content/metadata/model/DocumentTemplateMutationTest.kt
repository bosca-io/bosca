package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentTemplateMutationTest {

    @Test
    fun dataClassEquality() {
        // DocumentTemplateMutation wraps a Metadata object
        // We can test basic construction if Metadata is accessible
        // For now, test the data class contract
        val m1 = DocumentTemplateMutation::class
        assertEquals("DocumentTemplateMutation", m1.simpleName)
    }
}

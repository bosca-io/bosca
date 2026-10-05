package bosca.graphql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchItemTest {

    @Test
    fun keyIsPreserved() {
        val item = BatchItem<String, Int>("mykey")
        assertEquals("mykey", item.key)
    }

    @Test
    fun dataIsNullByDefault() {
        val item = BatchItem<String, Int>("key")
        assertNull(item.data)
    }

    @Test
    fun dataCanBeSet() {
        val item = BatchItem<String, Int>("key")
        item.data = 42
        assertEquals(42, item.data)
    }

    @Test
    fun dataCanBeSetToNull() {
        val item = BatchItem<String, Int>("key")
        item.data = 42
        item.data = null
        assertNull(item.data)
    }
}

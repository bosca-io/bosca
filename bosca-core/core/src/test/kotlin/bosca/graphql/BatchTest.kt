package bosca.graphql

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchTest {

    @Test
    fun `Batch stores and retrieves data by key`() = runBlocking {
        val batch = Batch<String, Int>(listOf("a", "b", "c"))
        batch.setData("a", 1)
        batch.setData("c", 3)
        assertEquals(1, batch.getData("a"))
        assertNull(batch.getData("b"))
        assertEquals(3, batch.getData("c"))
    }

    @Test
    fun `Batch setData with list populates items`() = runBlocking {
        val keys = listOf("x", "y")
        val batch = Batch<String, String>(keys)
        batch.setData(keys, listOf("val_x", "val_y"))
        assertEquals("val_x", batch.getData("x"))
        assertEquals("val_y", batch.getData("y"))
    }

    @Test
    fun `Batch setData with map populates items`() = runBlocking {
        val keys = listOf("a", "b")
        val batch = Batch<String, String>(keys)
        batch.setData(keys, mapOf("a" to "alpha", "b" to "beta"))
        assertEquals("alpha", batch.getData("a"))
        assertEquals("beta", batch.getData("b"))
    }

    @Test
    fun `Batch setData with index populates item`() = runBlocking {
        val batch = Batch<String, Int>(listOf("a", "b"))
        batch.setData(0, 10)
        batch.setData(1, 20)
        assertEquals(10, batch.getData("a"))
        assertEquals(20, batch.getData("b"))
    }

    @Test
    fun `Batch ensureNotNull fills null items with default`() = runBlocking {
        val batch = Batch<String, String>(listOf("a", "b"))
        batch.setData("a", "exists")
        batch.ensureNotNull("default")
        assertEquals("exists", batch.getData("a"))
        assertEquals("default", batch.getData("b"))
    }

    @Test
    fun `Batch keys returns original keys`() {
        val batch = Batch<Int, String>(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), batch.keys)
    }

    @Test
    fun `Batch getResults returns data without filter`() = runBlocking {
        val batch = Batch<String, Int>(listOf("a", "b"))
        batch.setData("a", 42)
        val results = batch.getResults()
        assertEquals(listOf(42, null), results)
    }

    @Test
    fun `Batch getResults applies filter`() = runBlocking {
        val batch = Batch<String, Int>(listOf("a", "b"))
        batch.setData("a", 1)
        batch.setData("b", 2)
        batch.filter = object : BatchFilter<String, Int> {
            override suspend fun filter(items: List<BatchItem<String, Int>>): List<Int?> {
                return items.map { item -> item.data?.takeIf { it > 1 } }
            }
        }
        val results = batch.getResults()
        assertEquals(listOf(null, 2), results)
    }

    @Test
    fun `Batch list and map setters ignore null values and unknown keys`() = runBlocking {
        val batch = Batch<String, Int>(listOf("a", "b"))
        batch.setData(listOf("a", "b"), listOf(1, null))
        batch.setData(listOf("a", "b"), mapOf("a" to null, "b" to 2))
        batch.setData("missing", 3)
        assertEquals(listOf(1, 2), batch.getResults())
    }

    @Test
    fun `BatchMapper mirrors every setter into original after mapping`() = runBlocking {
        val original = Batch<String, String>(listOf("a", "b", "c"))
        val mapped = BatchMapper<String, Int, String>(original) { it?.let { value -> "v$value" } }
        mapped.setData(listOf("a"), listOf(1))
        mapped.setData(listOf("b"), mapOf("b" to 2))
        mapped.setData(2, 3)
        mapped.setData("a", 4)

        assertEquals(listOf(4, 2, 3), mapped.getResults())
        assertEquals(listOf("v4", "v2", "v3"), original.getResults())
        mapped.originalEnsureNotNull("default")
        assertEquals(listOf("v4", "v2", "v3"), original.getResults())
    }

    @Test
    fun `BatchMapper leaves original null when mapper returns null`() = runBlocking {
        val original = Batch<String, String>(listOf("a"))
        val mapped = BatchMapper<String, Int, String>(original) { null }
        mapped.setData(0, 1)
        mapped.setData("a", 2)
        mapped.originalEnsureNotNull("fallback")
        assertEquals(listOf(2), mapped.getResults())
        assertEquals(listOf("fallback"), original.getResults())
    }

    @Test
    fun `BatchItem starts with null data`() {
        val item = BatchItem<String, Int>("key")
        assertEquals("key", item.key)
        assertNull(item.data)
    }
}

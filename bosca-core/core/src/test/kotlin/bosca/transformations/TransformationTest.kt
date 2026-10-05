package bosca.transformations

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates that custom implementations of the [Transformation] interface can be
 * created and invoked correctly, verifying the suspend-based transformation contract
 * used throughout the content processing pipeline.
 */
class TransformationTest {

    /** A transformation that doubles an integer within a named context. */
    private class DoubleTransformation : Transformation<String, Int, Int> {
        override suspend fun transform(context: String, item: Int): Int {
            return item * 2
        }
    }

    /** A transformation that uppercases a string, prefixed by context. */
    private class UpperCaseTransformation : Transformation<String, String, String> {
        override suspend fun transform(context: String, item: String): String {
            return "$context: ${item.uppercase()}"
        }
    }

    /** A transformation that converts a pair to a map entry. */
    private class PairToMapTransformation : Transformation<Unit, Pair<String, Int>, Map<String, Int>> {
        override suspend fun transform(context: Unit, item: Pair<String, Int>): Map<String, Int> {
            return mapOf(item)
        }
    }

    @Test
    fun `transformation doubles integer value`() = runTest {
        val transformation = DoubleTransformation()
        assertEquals(10, transformation.transform("ctx", 5))
        assertEquals(0, transformation.transform("ctx", 0))
        assertEquals(-4, transformation.transform("ctx", -2))
    }

    @Test
    fun `transformation uppercases with context prefix`() = runTest {
        val transformation = UpperCaseTransformation()
        assertEquals("PREFIX: HELLO", transformation.transform("PREFIX", "hello"))
    }

    @Test
    fun `transformation converts pair to map`() = runTest {
        val transformation = PairToMapTransformation()
        val result = transformation.transform(Unit, "key" to 42)
        assertEquals(mapOf("key" to 42), result)
    }

    @Test
    fun `transformation can be used polymorphically`() = runTest {
        val transformations: List<Transformation<String, Int, Int>> = listOf(
            DoubleTransformation(),
            object : Transformation<String, Int, Int> {
                override suspend fun transform(context: String, item: Int): Int = item + 1
            }
        )
        assertEquals(10, transformations[0].transform("", 5))
        assertEquals(6, transformations[1].transform("", 5))
    }

    @Test
    fun `transformation with Unit context works`() = runTest {
        val transformation = PairToMapTransformation()
        val result = transformation.transform(Unit, "a" to 1)
        assertEquals(1, result["a"])
    }
}

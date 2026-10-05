package bosca.bml.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the `.kt.map` reader — the unit-testable core of `.bml` breakpoint
 * mapping. The JSON shape mirrors `BmlSourceMap.toJson()` in `bml-compiler`.
 */
class BmlSourceMapIndexTest {

    // source line 5 produced two generated lines (8 and 9) — an open tag + child, say.
    private val json = """{"source":"pages/home.bml","mappings":[[7,3],[8,5],[9,5]]}"""

    @Test
    fun `parses the source path`() {
        assertEquals("pages/home.bml", BmlSourceMapIndex.parse(json).sourcePath)
    }

    @Test
    fun `resolves a generated line to its bml line`() {
        val index = BmlSourceMapIndex.parse(json)
        assertEquals(3, index.bmlLineFor(7))
        assertEquals(5, index.bmlLineFor(8))
        assertNull(index.bmlLineFor(99))
    }

    @Test
    fun `resolves a bml line to all generated lines`() {
        val index = BmlSourceMapIndex.parse(json)
        assertEquals(listOf(8, 9), index.generatedLinesFor(5))
        assertEquals(listOf(7), index.generatedLinesFor(3))
        assertEquals(8, index.firstGeneratedLineFor(5))
        assertTrue(index.generatedLinesFor(1).isEmpty())
        assertNull(index.firstGeneratedLineFor(1))
    }

    @Test
    fun `handles an escaped path and an empty mapping set`() {
        val index = BmlSourceMapIndex.parse("""{"source":"a\\b\"c.bml","mappings":[]}""")
        assertEquals("a\\b\"c.bml", index.sourcePath)
        assertNull(index.bmlLineFor(1))
        assertTrue(index.generatedLinesFor(1).isEmpty())
    }
}

package bosca.git.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffContextTest {
    /** New file: 101 lines; line 5 changed and a line inserted after old line 59 (new line 60). */
    private val source = (1..101).map {
        when {
            it == 5 -> "changed 5"
            it == 60 -> "inserted"
            it < 60 -> "line $it"
            else -> "line ${it - 1}"
        }
    }

    private fun context(old: Int, new: Int) = DiffLineView("CONTEXT", old, new, source[new - 1])

    /** The hunks the API returns for this change with three lines of context. */
    private val diff = DiffView(
        "file.txt",
        "file.txt",
        "MODIFY",
        listOf(
            hunk(2, 7, 2, 7, listOf(context(2, 2), context(3, 3), context(4, 4),
                DiffLineView("DELETE", 5, null, "line 5"), DiffLineView("ADD", null, 5, "changed 5"),
                context(6, 6), context(7, 7), context(8, 8))),
            hunk(57, 6, 57, 7, listOf(context(57, 57), context(58, 58), context(59, 59),
                DiffLineView("ADD", null, 60, "inserted"), context(60, 61), context(61, 62), context(62, 63))),
        ),
        newLineCount = 101,
    )

    private fun hunk(oldStart: Int, oldCount: Int, newStart: Int, newCount: Int, lines: List<DiffLineView>) =
        DiffHunkView(oldStart, oldCount, newStart, newCount, lines.size, lines)

    private fun DiffView.newNumbers() = hunks.flatMap { it.displayLines }.mapNotNull { it.newLineNumber }

    @Test
    fun `gaps cover hidden lines before, between and after hunks`() {
        val top = assertNotNull(diff.gap(0))
        assertEquals(listOf(1, 1, 0), listOf(top.newStart, top.newEnd, top.oldOffset))
        assertTrue(top.canExpandUp && !top.canExpandDown && top.expandsAtOnce)
        assertEquals("1 hidden line", top.label)
        assertEquals("@@ -2,7 +2,7 @@", top.heading)

        val middle = assertNotNull(diff.sections[1].gap)
        assertEquals(listOf(9, 56, 0), listOf(middle.newStart, middle.newEnd, middle.oldOffset))
        assertTrue(middle.canExpandUp && middle.canExpandDown && !middle.expandsAtOnce)
        assertEquals("48 hidden lines", middle.label)

        val bottom = assertNotNull(diff.bottomGap)
        assertEquals(listOf(64, 101, -1), listOf(bottom.newStart, bottom.newEnd, bottom.oldOffset))
        assertTrue(!bottom.canExpandUp && bottom.canExpandDown)
        assertEquals("", bottom.heading)
        assertTrue(diff.hasHiddenLines)
        assertFalse(diff.hasRevealedLines)
    }

    @Test
    fun `expanders reveal twenty lines toward the hunk they extend`() {
        val down = diff.expandGap(1, "down", source)
        assertEquals((9..28).toList(), down.hunks[0].after.map { it.newLineNumber })
        assertEquals((9..28).toList(), down.hunks[0].after.map { it.oldLineNumber })
        assertEquals(listOf(29, 56), down.gap(1)?.let { listOf(it.newStart, it.newEnd) })

        val up = down.expandGap(1, "up", source)
        assertEquals((37..56).toList(), up.hunks[1].before.map { it.newLineNumber })
        assertEquals("line 37", up.hunks[1].before.first().content)
        assertEquals("@@ -37,26 +37,27 @@", up.hunks[1].heading)
        val rest = assertNotNull(up.gap(1))
        assertEquals(8, rest.size)
        assertTrue(rest.expandsAtOnce)

        val closed = up.expandGap(1, "all", source)
        assertNull(closed.gap(1))
        assertTrue(closed.hasRevealedLines)

        val tail = diff.expandGap(2, "down", source)
        assertEquals((64..83).toList(), tail.hunks[1].after.map { it.newLineNumber })
        assertEquals((63..82).toList(), tail.hunks[1].after.map { it.oldLineNumber })
        assertEquals("line 63", tail.hunks[1].after.first().content)
    }

    @Test
    fun `expand all shows the whole file in order and collapse restores the diff`() {
        val all = diff.expandAll(source)
        assertEquals((1..101).toList(), all.newNumbers())
        assertFalse(all.hasHiddenLines)
        assertEquals(diff, all.collapseRevealed())
    }

    @Test
    fun `invalid expansions fail without changing the diff`() {
        assertFailsWith<IllegalArgumentException> { diff.expandGap(0, "down", source) }
        assertFailsWith<IllegalArgumentException> { diff.expandGap(2, "up", source) }
        assertFailsWith<IllegalArgumentException> { diff.expandGap(1, "sideways", source) }
        assertFailsWith<IllegalStateException> { diff.expandAll(source).expandGap(1, "all", source) }
        assertFailsWith<IllegalStateException> { diff.expandGap(1, "all", source.take(20)) }
    }

    @Test
    fun `discussion lines are revealed with surrounding context`() {
        val revealed = diff.reveal(listOf(40, 41, 10, 5, 200), source)
        val synthetic = revealed.hunks.filter { it.synthetic }
        assertEquals(listOf(9..13, 37..44), synthetic.map { it.firstNew..it.lastNew })
        assertTrue(synthetic.all { h -> h.lines.all { it.type == "CONTEXT" && it.oldLineNumber == it.newLineNumber } })
        assertEquals(listOf(2, 9, 37, 57), revealed.hunks.map { it.firstNew })
        assertNull(revealed.gap(1), "A window touching a hunk joins it without a gap")
        assertEquals(listOf(14, 36), revealed.gap(2)?.let { listOf(it.newStart, it.newEnd) })
        assertTrue(revealed.hides(30))
        assertFalse(revealed.hides(40))
        assertFalse(revealed.hasRevealedLines, "Discussion context is not an expansion the reviewer can hide")
        assertEquals(revealed, revealed.collapseRevealed())
        assertEquals(diff, diff.reveal(listOf(5, 60), source))
    }

    @Test
    fun `partially loaded hunks only extend after they are complete`() {
        val partial = diff.copy(hunks = diff.hunks.map { it.copy(totalLineCount = it.lines.size + 5) })
        val middle = assertNotNull(partial.gap(1))
        assertTrue(middle.canExpandUp && !middle.canExpandDown)
        assertNull(partial.bottomGap)
        assertEquals((37..56).toList(), partial.expandGap(1, "all", source).hunks[1].before.map { it.newLineNumber }.takeLast(20))
    }

    @Test
    fun `files without new text have nothing to reveal`() {
        val deleted = DiffView("gone.txt", null, "DELETE", listOf(hunk(1, 2, 0, 0, listOf(
            DiffLineView("DELETE", 1, null, "a"), DiffLineView("DELETE", 2, null, "b")))))
        assertNull(deleted.gap(0))
        assertFalse(deleted.hasHiddenLines)
        val renamed = DiffView("a.txt", "b.txt", "RENAME", listOf(hunk(0, 0, 0, 0, emptyList())), newLineCount = 12)
        assertFalse(renamed.hasHiddenLines)
        assertTrue(renamed.emptyTextChange)
    }

    @Test
    fun `file lines match diff numbering`() {
        assertEquals(listOf("a", "b"), fileLines("a\nb\n"))
        assertEquals(listOf("a", "b"), fileLines("a\nb"))
        assertEquals(listOf("a", "", "b"), fileLines("a\n\nb\n"))
        assertEquals(emptyList(), fileLines(""))
        assertEquals(listOf("a\r", "b\r"), fileLines("a\r\nb\r\n"))
    }

    @Test
    fun `additions and deletions count loaded lines`() {
        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)
    }
}

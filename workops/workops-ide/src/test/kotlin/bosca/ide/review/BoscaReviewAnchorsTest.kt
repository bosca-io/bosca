package bosca.ide.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoscaReviewAnchorsTest {
    @Test
    fun `new-side anchors preserve new line identity for additions and modifications`() {
        val added = file("ADD", null, "new.kt", BoscaDiffLine("ADD", null, 7, "new"))
        val modified = file("MODIFY", "a.kt", "a.kt", BoscaDiffLine("CONTEXT", 9, 11, "same"))

        assertEquals(BoscaReviewAnchor(null, 7), BoscaReviewAnchors.find(added, BoscaDiffSide.NEW, 7))
        assertEquals(BoscaReviewAnchor(9, 11), BoscaReviewAnchors.find(modified, BoscaDiffSide.NEW, 11))
    }

    @Test
    fun `old-side anchors preserve deleted and renamed source lines`() {
        val deleted = file("DELETE", "gone.kt", null, BoscaDiffLine("DELETE", 4, null, "gone"))
        val renamed = file("RENAME", "old.kt", "new.kt", BoscaDiffLine("CONTEXT", 2, 2, "same"))

        assertEquals(BoscaReviewAnchor(4, null), BoscaReviewAnchors.find(deleted, BoscaDiffSide.OLD, 4))
        assertEquals(BoscaReviewAnchor(2, 2), BoscaReviewAnchors.find(renamed, BoscaDiffSide.OLD, 2))
    }

    @Test
    fun `missing and force-pushed anchors are rejected instead of remapped`() {
        val file = file("MODIFY", "a.kt", "a.kt", BoscaDiffLine("ADD", null, 20, "new"))

        assertNull(BoscaReviewAnchors.find(file, BoscaDiffSide.NEW, 19))
        assertNull(BoscaReviewAnchors.find(file, BoscaDiffSide.OLD, 20))
        assertNull(BoscaReviewAnchors.find(file, BoscaDiffSide.NEW, 0))
    }

    private fun file(type: String, old: String?, new: String?, line: BoscaDiffLine) = BoscaDiffFile(
        oldPath = old,
        newPath = new,
        changeType = type,
        hunks = listOf(BoscaDiffHunk(1, 1, 1, 1, listOf(line))),
    )
}

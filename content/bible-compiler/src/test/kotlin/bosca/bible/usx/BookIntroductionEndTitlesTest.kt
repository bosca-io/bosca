package bosca.bible.usx

import bosca.bible.BookIntroductionEndTitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookIntroductionEndTitlesTest {

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "mt"))
        val biet = BookIntroductionEndTitles(attrs, null, Position(0))
        assertEquals(BookIntroductionEndTitleStyle.mt, biet.style)
    }

    @Test
    fun missingStyleThrowsError() {
        val attrs = Attributes(emptyMap())
        assertFailsWith<IllegalStateException> {
            BookIntroductionEndTitles(attrs, null, Position(0))
        }
    }

    @Test
    fun htmlClassMatchesStyleToString() {
        val attrs = Attributes(mapOf("STYLE" to "imt1"))
        val biet = BookIntroductionEndTitles(attrs, null, Position(0))
        assertEquals(BookIntroductionEndTitleStyle.imt1.toString(), biet.htmlClass)
    }
}

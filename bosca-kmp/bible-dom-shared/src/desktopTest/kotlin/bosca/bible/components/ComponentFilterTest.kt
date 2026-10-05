package bosca.bible.components

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComponentFilterTest {

    @Test
    fun testFilterSequentialVerses() {
        val references = Reference("MAT.5.13+MAT.5.14+MAT.5.15+MAT.5.16").references
        
        val container = ComponentContainer(
            ContainerType.PARAGRAPH,
            listOf(
                VerseStart(Reference("MAT.5.13")),
                Text("Verse 13", null),
                VerseEnd(),
                VerseStart(Reference("MAT.5.14")),
                Text("Verse 14", null),
                VerseEnd(),
                VerseStart(Reference("MAT.5.15")),
                Text("Verse 15", null),
                VerseEnd(),
                VerseStart(Reference("MAT.5.16")),
                Text("Verse 16", null),
                VerseEnd()
            ),
            null
        )
        
        val filtered = container.filter(Reference("MAT.5.13+MAT.5.14+MAT.5.15+MAT.5.16"))
        assertNotNull(filtered)
        val filteredContainer = filtered as ComponentContainer
        assertEquals(12, filteredContainer.components.size)
    }

    @Test
    fun testFilterVerseRange() {
        // This simulates requesting verses that are part of a range in the USX
        val references = Reference("MAT.5.13+MAT.5.14+MAT.5.15")
        
        val container = ComponentContainer(
            ContainerType.PARAGRAPH,
            listOf(
                VerseStart(Reference("MAT.5.13")),
                Text("Verse 13", null),
                VerseEnd(),
                VerseStart(Reference("MAT.5.14")),
                Text("Verse 14", null),
                VerseEnd(),
                VerseStart(Reference("MAT.5.15")),
                Text("Verse 15", null),
                VerseEnd(),
            ),
            null
        )
        
        val filtered = container.filter(references)
        assertNotNull(filtered)
        val filteredContainer = filtered as ComponentContainer
        // If it works correctly, it should include Verse 15-16
        assertEquals(9, filteredContainer.components.size)
    }
}

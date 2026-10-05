package bosca.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageDetailsTest {

    // --- PageDetails ---

    @Test
    fun `PageDetails stores title and siteName`() {
        val details = PageDetails(siteName = "My Site", title = "Home")
        assertEquals("My Site", details.siteName)
        assertEquals("Home", details.title)
    }

    @Test
    fun `PageDetails description defaults to null`() {
        val details = PageDetails(title = "T")
        assertNull(details.description)
    }

    @Test
    fun `PageDetails logo defaults to null`() {
        val details = PageDetails(title = "T")
        assertNull(details.logo)
    }

    @Test
    fun `PageDetails siteName defaults to site dot name`() {
        val details = PageDetails(title = "T")
        assertEquals("site.name", details.siteName)
    }

    @Test
    fun `PageDetails stores all properties`() {
        val logo = Logo(url = "https://example.com/logo.png", title = "Logo")
        val details = PageDetails(
            siteName = "Site", title = "Page",
            description = "A page", logo = logo
        )
        assertEquals("A page", details.description)
        assertEquals(logo, details.logo)
    }

    // --- Logo ---

    @Test
    fun `Logo stores url and title`() {
        val logo = Logo(url = "https://example.com/img.png", title = "Brand Logo")
        assertEquals("https://example.com/img.png", logo.url)
        assertEquals("Brand Logo", logo.title)
    }

    @Test
    fun `Logo title can be null`() {
        val logo = Logo(url = "https://example.com/img.png", title = null)
        assertNull(logo.title)
    }
}

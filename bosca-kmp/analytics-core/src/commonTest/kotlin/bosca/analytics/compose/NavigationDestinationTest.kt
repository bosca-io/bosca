package bosca.analytics.compose

import bosca.analytics.api.AnalyticsNavigationRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationDestinationTest {
    @Test
    fun `route metadata controls the analytics destination`() {
        val destination = navigationDestination(BookRoute("42"))

        assertEquals("/books/42", destination.page.path)
        assertEquals("Book", destination.page.title)
        assertEquals("BookRoute", destination.routeType)
        assertEquals(mapOf("book_id" to "42"), destination.extras)
    }

    @Test
    fun `string and route class names produce normalized paths`() {
        val stringDestination = navigationDestination("settings/profile")
        val absoluteDestination = navigationDestination("/help")
        val classDestination = navigationDestination(AccountDetailsScreen)
        val routeSuffix = navigationDestination(CatalogRoute)
        val pageSuffix = navigationDestination(WelcomePage)

        assertEquals("/settings/profile", stringDestination.page.path)
        assertEquals("/help", absoluteDestination.page.path)
        assertEquals("/account-details", classDestination.page.path)
        assertEquals("/catalog", routeSuffix.page.path)
        assertEquals("/welcome", pageSuffix.page.path)
        assertNull(classDestination.page.title)
        assertEquals(emptyMap(), classDestination.extras)
    }

    private data class BookRoute(val id: String) : AnalyticsNavigationRoute {
        override val analyticsPath = "/books/$id"
        override val analyticsTitle = "Book"
        override val analyticsExtras = mapOf("book_id" to id)
    }

    private data object AccountDetailsScreen

    private data object CatalogRoute

    private data object WelcomePage
}

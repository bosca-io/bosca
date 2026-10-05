package bosca.pages.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PageControllerAnnotationTest {

    @PageController(path = "/test")
    class DefaultPageController

    @PageController(path = "/form", method = PageMethod.POST, authentication = PageAuthentication.REQUIRED)
    class PostPageController

    @PageController(path = "/public", authentication = PageAuthentication.NONE)
    class PublicPageController

    @Test
    fun `PageController stores path`() {
        val annotation = DefaultPageController::class.java.getAnnotation(PageController::class.java)
        assertNotNull(annotation)
        assertEquals("/test", annotation.path)
    }

    @Test
    fun `PageController default method is GET`() {
        val annotation = DefaultPageController::class.java.getAnnotation(PageController::class.java)
        assertNotNull(annotation)
        assertEquals(PageMethod.GET, annotation.method)
    }

    @Test
    fun `PageController default authentication is OPTIONAL`() {
        val annotation = DefaultPageController::class.java.getAnnotation(PageController::class.java)
        assertNotNull(annotation)
        assertEquals(PageAuthentication.OPTIONAL, annotation.authentication)
    }

    @Test
    fun `PageController with POST method`() {
        val annotation = PostPageController::class.java.getAnnotation(PageController::class.java)
        assertNotNull(annotation)
        assertEquals(PageMethod.POST, annotation.method)
    }

    @Test
    fun `PageController with REQUIRED authentication`() {
        val annotation = PostPageController::class.java.getAnnotation(PageController::class.java)
        assertNotNull(annotation)
        assertEquals(PageAuthentication.REQUIRED, annotation.authentication)
    }

    @Test
    fun `PageController with NONE authentication`() {
        val annotation = PublicPageController::class.java.getAnnotation(PageController::class.java)
        assertNotNull(annotation)
        assertEquals(PageAuthentication.NONE, annotation.authentication)
    }

}

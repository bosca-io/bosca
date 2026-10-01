package bosca.routes.annotations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RouteControllerAnnotationTest {

    @RouteController(path = "/api/test")
    class DefaultRouteController

    @RouteController(path = "/api/create", method = RouteMethod.POST, authentication = RouteAuthentication.REQUIRED)
    class PostRouteController

    @RouteController(path = "/api/public", method = RouteMethod.GET, authentication = RouteAuthentication.NONE)
    class PublicRouteController

    @RouteController(path = "/api/update", method = RouteMethod.PUT)
    class PutRouteController

    @RouteController(path = "/api/remove", method = RouteMethod.DELETE)
    class DeleteRouteController

    @RouteController(path = "/api/patch", method = RouteMethod.PATCH)
    class PatchRouteController

    @Test
    fun `RouteController stores path`() {
        val annotation = DefaultRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals("/api/test", annotation.path)
    }

    @Test
    fun `RouteController default method is GET`() {
        val annotation = DefaultRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteMethod.GET, annotation.method)
    }

    @Test
    fun `RouteController default authentication is OPTIONAL`() {
        val annotation = DefaultRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteAuthentication.OPTIONAL, annotation.authentication)
    }

    @Test
    fun `RouteController with POST method`() {
        val annotation = PostRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteMethod.POST, annotation.method)
    }

    @Test
    fun `RouteController with REQUIRED authentication`() {
        val annotation = PostRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteAuthentication.REQUIRED, annotation.authentication)
    }

    @Test
    fun `RouteController with NONE authentication`() {
        val annotation = PublicRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteAuthentication.NONE, annotation.authentication)
    }

    @Test
    fun `RouteController with PUT method`() {
        val annotation = PutRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteMethod.PUT, annotation.method)
    }

    @Test
    fun `RouteController with DELETE method`() {
        val annotation = DeleteRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteMethod.DELETE, annotation.method)
    }

    @Test
    fun `RouteController with PATCH method`() {
        val annotation = PatchRouteController::class.java.getAnnotation(RouteController::class.java)
        assertNotNull(annotation)
        assertEquals(RouteMethod.PATCH, annotation.method)
    }

}

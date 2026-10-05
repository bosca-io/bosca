package bosca.server.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteDescriptionTest {

    @Test
    fun `collects simple HTTP routes`() {
        val router = Router()
        router.get("/users") {}
        router.post("/users") {}
        val descriptions = router.collectRouteDescriptions()
        assertEquals(2, descriptions.size)
        assertTrue(descriptions.any { it.method == "GET" && it.path == "/users" })
        assertTrue(descriptions.any { it.method == "POST" && it.path == "/users" })
    }

    @Test
    fun `collects nested HTTP routes with full path`() {
        val router = Router()
        router.route("/api") {
            route("/v1") {
                get("/users") {}
            }
        }
        val descriptions = router.collectRouteDescriptions()
        assertEquals(1, descriptions.size)
        assertEquals("/api/v1/users", descriptions[0].path)
        assertEquals("GET", descriptions[0].method)
        assertEquals(RouteType.HTTP, descriptions[0].type)
    }

    @Test
    fun `collects WebSocket routes`() {
        val router = Router()
        router.route("/api") {
            webSocket("/ws") {}
        }
        val descriptions = router.collectRouteDescriptions()
        assertEquals(1, descriptions.size)
        assertEquals(RouteType.WEBSOCKET, descriptions[0].type)
        assertEquals("/api/ws", descriptions[0].path)
    }

    @Test
    fun `collects SSE routes`() {
        val router = Router()
        router.sse("/events") {}
        val descriptions = router.collectRouteDescriptions()
        assertEquals(1, descriptions.size)
        assertEquals(RouteType.SSE, descriptions[0].type)
        assertEquals("/events", descriptions[0].path)
    }

    @Test
    fun `marks authenticated routes`() {
        val router = Router()
        router.authenticate("jwt") {
            get("/secret") {}
        }
        val descriptions = router.collectRouteDescriptions()
        assertEquals(1, descriptions.size)
        assertTrue(descriptions[0].authenticated)
    }

    @Test
    fun `unauthenticated routes are not marked`() {
        val router = Router()
        router.get("/public") {}
        val descriptions = router.collectRouteDescriptions()
        assertEquals(1, descriptions.size)
        assertEquals(false, descriptions[0].authenticated)
    }

    @Test
    fun `empty router produces empty list`() {
        val router = Router()
        assertTrue(router.collectRouteDescriptions().isEmpty())
    }

    @Test
    fun `collects all HTTP methods`() {
        val router = Router()
        router.get("/r") {}
        router.post("/r") {}
        router.put("/r") {}
        router.delete("/r") {}
        router.patch("/r") {}
        val descriptions = router.collectRouteDescriptions()
        assertEquals(5, descriptions.size)
        val methods = descriptions.map { it.method }.toSet()
        assertEquals(setOf("GET", "POST", "PUT", "DELETE", "PATCH"), methods)
    }

    @Test
    fun `deeply nested routes produce correct full paths`() {
        val router = Router()
        router.route("/a") {
            route("/b") {
                route("/c") {
                    get("/d") {}
                }
            }
        }
        val descriptions = router.collectRouteDescriptions()
        assertEquals(1, descriptions.size)
        assertEquals("/a/b/c/d", descriptions[0].path)
    }

    @Test
    fun `mixed route types in same router`() {
        val router = Router()
        router.get("/api") {}
        router.webSocket("/ws") {}
        router.sse("/events") {}
        val descriptions = router.collectRouteDescriptions()
        assertEquals(3, descriptions.size)
        assertEquals(1, descriptions.count { it.type == RouteType.HTTP })
        assertEquals(1, descriptions.count { it.type == RouteType.WEBSOCKET })
        assertEquals(1, descriptions.count { it.type == RouteType.SSE })
    }

    @Test
    fun `root and pathless children retain the supplied parent path`() {
        val router = Router()
        router.route("/") {
            get("/root") {}
        }
        router.route("") {
            sse {}
        }

        val descriptions = router.collectRouteDescriptions("/parent")

        assertTrue(descriptions.any { it.path == "/parent/root" })
        assertTrue(descriptions.any { it.type == RouteType.SSE && it.path == "/parent" })
    }
}

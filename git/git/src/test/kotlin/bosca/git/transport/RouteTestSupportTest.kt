package bosca.git.transport

import kotlin.test.Test
import kotlin.test.assertEquals

/** Sanity checks for the [recordedCall] harness itself. */
class RouteTestSupportTest {
    @Test
    fun `response header calls are recorded`() {
        val (call, rec) = recordedCall()
        call.response.header("Cache-Control", "no-cache")
        assertEquals("no-cache", rec.headers["Cache-Control"])
    }
}

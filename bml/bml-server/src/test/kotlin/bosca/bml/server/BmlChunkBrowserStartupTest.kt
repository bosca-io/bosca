package bosca.bml.server

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BmlChunkBrowserStartupTest {
    @Test
    fun `DevTools readiness waits for the initial page after an empty target list`() {
        var reads = 0
        val page = JsonObject(mapOf("type" to JsonPrimitive("page")))
        val target = awaitChunkBrowserPageTarget(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
            isAlive = { true },
            readTargets = { if (++reads == 1) emptyList() else listOf(page) },
        )
        assertEquals(page, target)
        assertEquals(2, reads)
    }

    @Test
    fun `an expired startup deadline does not query DevTools`() {
        assertNull(awaitChunkBrowserPageTarget(
            System.nanoTime() - 1,
            isAlive = { true },
            readTargets = { error("Expired startup must not query DevTools") },
        ))
    }
}

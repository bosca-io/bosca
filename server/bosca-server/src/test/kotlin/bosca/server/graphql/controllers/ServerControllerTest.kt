package bosca.server.graphql.controllers

import kotlin.test.Test
import kotlin.test.assertNotNull
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertTrue

class ServerControllerTest {

    private val controller = ServerController()

    @Test
    fun `now returns current OffsetDateTime`() {
        val before = OffsetDateTime.now()
        val result = controller.now()
        val after = OffsetDateTime.now()

        assertNotNull(result)
        assertTrue(
            ChronoUnit.SECONDS.between(before, result) <= 1,
            "now() should return approximately current time"
        )
    }
}
